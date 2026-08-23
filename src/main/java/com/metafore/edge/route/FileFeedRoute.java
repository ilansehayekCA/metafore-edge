package com.metafore.edge.route;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.metafore.edge.config.EdgeConfig;
import com.metafore.edge.topic.TopicBuilder;
import org.apache.camel.builder.RouteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * G5 / adr-228 — scheduled collection of a partner-delivered file.
 *
 * <p>Inbound data could arrive only as a document through a channel door, or from
 * a live connected system. A counterparty that drops a periodic file could not be
 * integrated at all — and periodic bulk delivery is how a large share of
 * enterprise partners actually send data: settlement, status, roster, remittance.
 *
 * <p>At the EDGE rather than in core, because the drop location belongs to the
 * customer. A customer who wants the file landing on their own SFTP, or produced
 * by an internal system instead of fetched from a third party, changes only this
 * configuration; core never learns where the file came from. Building the
 * consumer in core would have foreclosed exactly that.
 *
 * <p>Configured per feed via a control message on the routes topic:
 * <pre>{
 *   "op":        "file_feed",
 *   "feed_id":   "partner-status",
 *   "uri":       "file:/data/in/partner?move=.done&readLock=changed",
 *   "delay_ms":  86400000,
 *   "format":    "csv",          // csv | json | raw
 *   "match_on":  "external_id"   // the column that identifies the subject
 * }</pre>
 *
 * <p>Each row is published to the events topic as its own message, so core's
 * existing arrival pipeline handles one row like any other inbound fact. The
 * route does NOT interpret the rows: what a column means is the app's business,
 * and the edge holds no notion of it.
 */
public class FileFeedRoute extends RouteBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(FileFeedRoute.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final CsvMapper CSV = new CsvMapper();

    /** Guard against a malformed feed spinning the scheduler. */
    private static final long MIN_DELAY_MS = 1000L;

    private final EdgeConfig config;
    private final TopicBuilder topics;

    public FileFeedRoute(EdgeConfig config, TopicBuilder topics) {
        this.config = config;
        this.topics = topics;
    }

    @Override
    public void configure() {
        // Feeds are declared at runtime, so this route listens for the
        // declaration and starts a consumer per feed rather than hardcoding one.
        from("paho:" + topics.controlRoutes()
                + "?brokerUrl=" + config.brokerUrl()
                + "&clientId=file-feed-" + config.controllerId()
                + "-" + topics.tenantId())
            .routeId("file-feed-control-" + topics.tenantId())
            .process(ex -> {
                String body = ex.getIn().getBody(String.class);
                Map<?, ?> cmd = MAPPER.readValue(body, Map.class);
                if (!"file_feed".equals(String.valueOf(cmd.get("op")))) {
                    return;   // not ours; RouteExecutorRoute handles the rest
                }
                startFeed(cmd);
            });
    }

    /**
     * Bring up (or replace) one feed's consumer. Idempotent on {@code feed_id}:
     * re-declaring a feed replaces its consumer rather than adding a second one,
     * so a redeploy cannot silently double-read every file.
     */
    private void startFeed(Map<?, ?> cmd) throws Exception {
        String feedId = String.valueOf(cmd.get("feed_id"));
        String uri = String.valueOf(cmd.get("uri"));
        if (feedId == null || feedId.isBlank() || uri == null || uri.isBlank()) {
            LOG.warn("[file-feed] declaration missing feed_id or uri: {}", cmd);
            return;
        }
        if (!uri.startsWith("file:") && !uri.startsWith("sftp:") && !uri.startsWith("ftp:")) {
            // Refuse rather than hand an arbitrary string to the component
            // registry — a feed is a file drop, not a way to reach anything.
            LOG.error("[file-feed] {} refused: uri must be file:, sftp: or ftp:, got {}",
                      feedId, uri);
            return;
        }
        long delay = asLong(cmd.get("delay_ms"), 86_400_000L);
        if (delay < MIN_DELAY_MS) {
            LOG.warn("[file-feed] {} delay {}ms below the floor; using {}ms",
                     feedId, delay, MIN_DELAY_MS);
            delay = MIN_DELAY_MS;
        }
        Object rawFormat = cmd.get("format");
        String format = rawFormat == null ? "csv" : String.valueOf(rawFormat);
        String routeId = "file-feed-" + topics.tenantId() + "-" + feedId;

        if (getContext().getRoute(routeId) != null) {
            LOG.info("[file-feed] replacing existing consumer {}", routeId);
            getContext().getRouteController().stopRoute(routeId);
            getContext().removeRoute(routeId);
        }

        final String fFeedId = feedId;
        final String fFormat = format;
        final long fDelay = delay;
        getContext().addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from(uri + (uri.contains("?") ? "&" : "?") + "delay=" + fDelay)
                    .routeId(routeId)
                    .process(ex -> {
                        String name = String.valueOf(
                            ex.getIn().getHeader("CamelFileName"));
                        byte[] content = ex.getIn().getBody(byte[].class);
                        List<Map<String, Object>> rows = parse(content, fFormat);
                        LOG.info("[file-feed] {} read {} row(s) from {}",
                                 fFeedId, rows.size(), name);
                        ex.getIn().setHeader("feedId", fFeedId);
                        ex.getIn().setHeader("fileName", name);
                        ex.getIn().setBody(rows);
                    })
                    .split(body())
                    .process(ex -> {
                        // One row, one message — so core's arrival pipeline sees
                        // a row exactly as it sees any other inbound fact, and a
                        // single bad row cannot lose the file.
                        Map<String, Object> envelope = new HashMap<>();
                        envelope.put("kind", "file_feed_row");
                        envelope.put("feed_id", ex.getIn().getHeader("feedId"));
                        envelope.put("file_name", ex.getIn().getHeader("fileName"));
                        envelope.put("tenant_id", topics.tenantId());
                        envelope.put("controller_id", config.controllerId());
                        envelope.put("row", ex.getIn().getBody());
                        ex.getIn().setBody(MAPPER.writeValueAsString(envelope));
                    })
                    .to("paho:" + topics.telemetryEvents()
                        + "?brokerUrl=" + config.brokerUrl()
                        + "&clientId=file-feed-pub-" + fFeedId);
            }
        });
        LOG.info("[file-feed] {} consuming {} every {}ms as {}",
                 feedId, uri, delay, format);
    }

    /**
     * Rows from the file. The edge parses SHAPE and nothing else — it does not
     * know what a column means, which is what keeps a partner's vocabulary out
     * of the platform.
     */
    static List<Map<String, Object>> parse(byte[] content, String format)
            throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (content == null || content.length == 0) {
            return rows;
        }
        if ("json".equalsIgnoreCase(format)) {
            Object parsed = MAPPER.readValue(content, Object.class);
            if (parsed instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        rows.add(castRow(m));
                    }
                }
            } else if (parsed instanceof Map<?, ?> m) {
                rows.add(castRow(m));
            }
            return rows;
        }
        if ("raw".equalsIgnoreCase(format)) {
            Map<String, Object> one = new HashMap<>();
            one.put("raw", new String(content));
            rows.add(one);
            return rows;
        }
        // CSV with a header row — the common shape for a partner status file.
        CsvSchema schema = CsvSchema.emptySchema().withHeader();
        try (MappingIterator<Map<String, Object>> it =
                 CSV.readerFor(Map.class).with(schema).readValues(content)) {
            while (it.hasNext()) {
                rows.add(it.next());
            }
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castRow(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    static long asLong(Object v, long dflt) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** Visible for the unit test — the file the route would consume. */
    static File asFile(String path) {
        return new File(path);
    }
}
