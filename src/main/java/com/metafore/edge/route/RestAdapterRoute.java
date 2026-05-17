package com.metafore.edge.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metafore.edge.config.EdgeConfig;
import com.metafore.edge.message.MessageFactory;
import com.metafore.edge.topic.TopicBuilder;
import org.apache.camel.builder.RouteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Camel route that listens for write-back commands via MQTT and executes
 * HTTP REST calls against external APIs (ServiceNow, JIRA, Salesforce, etc.).
 *
 * <p>Inbound command payload (JSON on the write-back control topic):
 * <pre>{
 *   "action_id": "wb-001",
 *   "method":    "POST",           // GET, POST, PATCH, DELETE
 *   "url":       "https://...",
 *   "headers":   {"Authorization": "Bearer ...", "Content-Type": "application/json"},
 *   "body":      { ... },          // optional — serialised to JSON
 *   "old_values": { ... },         // optional — snapshot before mutation
 *   "new_values": { ... }          // optional — intended new values
 * }</pre>
 *
 * <p>Result is published to the telemetry/route-results topic using the
 * existing {@link MessageFactory#writeBackResult} format.
 */
public class RestAdapterRoute extends RouteBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(RestAdapterRoute.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PATCH", "PUT", "DELETE");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final EdgeConfig config;
    private final TopicBuilder topics;
    private final HttpClient httpClient;

    public RestAdapterRoute(EdgeConfig config, TopicBuilder topics) {
        this(config, topics, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    /** Test-friendly constructor that accepts a pre-built HttpClient. */
    RestAdapterRoute(EdgeConfig config, TopicBuilder topics, HttpClient httpClient) {
        this.config = config;
        this.topics = topics;
        this.httpClient = httpClient;
    }

    @Override
    public void configure() {
        // Phase 14.18 — per-tenant routeId so each tenant's write-back
        // subscription is independently named in Camel diagnostics.
        String routeId = "rest-adapter-" + topics.tenantId();
        from("paho:" + topics.controlWriteBack() + "?brokerUrl=" + config.brokerUrl())
            .routeId(routeId)
            .log("Write-back command received: ${body}")
            .unmarshal().json(Map.class)
            .process(exchange -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> cmd = exchange.getIn().getBody(Map.class);

                Map<String, Object> result = executeHttpCall(cmd);
                exchange.getIn().setBody(result);
            })
            .marshal().json()
            .to("paho:" + topics.telemetryRouteResults()
                + "?brokerUrl=" + config.brokerUrl())
            .log("Write-back result published");
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> executeHttpCall(Map<String, Object> cmd) {
        String actionId = (String) cmd.getOrDefault("action_id", UUID.randomUUID().toString());
        String method = ((String) cmd.getOrDefault("method", "")).toUpperCase();
        String url = (String) cmd.get("url");
        Map<String, String> headers = (Map<String, String>) cmd.getOrDefault("headers", Map.of());
        Object bodyObj = cmd.get("body");
        Map<String, Object> oldValues = (Map<String, Object>) cmd.get("old_values");
        Map<String, Object> newValues = (Map<String, Object>) cmd.get("new_values");

        // Validate method
        if (!ALLOWED_METHODS.contains(method)) {
            return MessageFactory.writeBackResult(config, actionId,
                "error", 0, "Unsupported HTTP method: " + method, oldValues, newValues);
        }

        // Validate URL
        if (url == null || url.isBlank()) {
            return MessageFactory.writeBackResult(config, actionId,
                "error", 0, "Missing required field: url", oldValues, newValues);
        }

        try {
            // Build request
            String bodyJson = bodyObj != null ? MAPPER.writeValueAsString(bodyObj) : null;
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT);

            // Apply headers
            for (Map.Entry<String, String> h : headers.entrySet()) {
                reqBuilder.header(h.getKey(), h.getValue());
            }

            // Set method + body
            HttpRequest.BodyPublisher publisher = bodyJson != null
                ? HttpRequest.BodyPublishers.ofString(bodyJson)
                : HttpRequest.BodyPublishers.noBody();

            switch (method) {
                case "GET"    -> reqBuilder.GET();
                case "DELETE" -> reqBuilder.DELETE();
                case "POST"   -> reqBuilder.POST(publisher);
                case "PUT"    -> reqBuilder.PUT(publisher);
                case "PATCH"  -> reqBuilder.method("PATCH", publisher);
                default       -> reqBuilder.method(method, publisher);
            }

            // Execute
            long start = System.currentTimeMillis();
            HttpResponse<String> response = httpClient.send(
                reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;

            LOG.info("REST {} {} -> {} ({}ms)", method, url,
                response.statusCode(), latency);

            String status = response.statusCode() >= 200 && response.statusCode() < 300
                ? "success" : "error";

            return MessageFactory.writeBackResult(config, actionId,
                status, response.statusCode(), response.body(), oldValues, newValues);

        } catch (Exception e) {
            LOG.error("REST call failed: {} {}", method, url, e);
            return MessageFactory.writeBackResult(config, actionId,
                "error", 0, "Exception: " + e.getMessage(), oldValues, newValues);
        }
    }
}
