package com.metafore.edge.route;

import com.metafore.edge.config.EdgeConfig;
import com.metafore.edge.message.MessageFactory;
import com.metafore.edge.service.Capabilities;
import com.metafore.edge.service.ConnectionTester;
import com.metafore.edge.service.DataSourceRegistry;
import com.metafore.edge.service.McpExecutor;
import com.metafore.edge.service.ShellExecutor;
import com.metafore.edge.service.SqlExecutor;
import com.metafore.edge.topic.TopicBuilder;
import org.apache.camel.builder.RouteBuilder;

import javax.sql.DataSource;
import java.util.*;

public class RouteExecutorRoute extends RouteBuilder {

    private final EdgeConfig config;
    private final TopicBuilder topics;
    private final DataSourceRegistry dsRegistry;
    private final McpExecutor mcpExecutor;

    public RouteExecutorRoute(EdgeConfig config, TopicBuilder topics,
                              DataSourceRegistry dsRegistry) {
        this(config, topics, dsRegistry, new McpExecutor());
    }

    /**
     * adr-158 — test-friendly constructor accepting a pre-built
     * {@link McpExecutor} (wrapping a mock HttpClient) so the MCP
     * dispatch branch is exercised without a live MCP server.
     */
    RouteExecutorRoute(EdgeConfig config, TopicBuilder topics,
                       DataSourceRegistry dsRegistry, McpExecutor mcpExecutor) {
        this.config = config;
        this.topics = topics;
        this.dsRegistry = dsRegistry;
        this.mcpExecutor = mcpExecutor;
    }

    @Override
    public void configure() {
        // Phase 14.18 — routeId is suffixed with the tenant slug so a
        // multi-tenant edge runs one independently-named route per
        // tenant (route-executor-<slug>) and each subscription resolves
        // to a tenant-scoped control topic. The tenant_id is implicit in
        // the topic path; no header decode required.
        String camelRouteId = "route-executor-" + topics.tenantId();
        from("paho:" + topics.controlRoutes() + "?brokerUrl=" + config.brokerUrl())
            .routeId(camelRouteId)
            .log("Route command received on tenant=" + topics.tenantId() + ": ${body}")
            .unmarshal().json(Map.class)
            .process(exchange -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> cmd = exchange.getIn().getBody(Map.class);
                String action = (String) cmd.getOrDefault("action", "");
                String routeId = (String) cmd.getOrDefault("route_id", "");

                Map<String, Object> result = enforceCapability(action, routeId, cmd);
                if (result != null) {
                    exchange.getIn().setBody(result);
                    return;
                }

                if ("deploy".equals(action) || "execute".equals(action)) {
                    result = handleExecute(routeId, cmd);
                } else if ("connect_and_ping".equals(action)) {
                    // Phase 14.12 / MTBC.T1 — ephemeral connection
                    // probe. Does NOT touch dsRegistry; does NOT route
                    // through SqlExecutor whitelist. See
                    // ConnectionTester for the typed-reason taxonomy.
                    result = handleConnectAndPing(routeId, cmd);
                } else if ("remove".equals(action)) {
                    dsRegistry.remove(routeId);
                    result = MessageFactory.routeResult(config, routeId,
                        "success", null, 0, null, null, null, null, null);
                } else {
                    result = MessageFactory.routeResult(config, routeId,
                        "error", null, 0, null, null, null,
                        "Unknown action: " + action, null);
                }

                exchange.getIn().setBody(result);
            })
            .marshal().json()
            .to("paho:" + topics.telemetryRouteResults()
                + "?brokerUrl=" + config.brokerUrl())
            .log("Route result published");
    }

    /**
     * CCA.T2 / compass ba53231a — capability enforcement at the
     * dispatch boundary.
     *
     * <p>Returns:
     * <ul>
     *   <li>{@code null} when the action is unknown (let the existing
     *       "Unknown action" branch surface it — that's a parse error,
     *       not a capability denial) or when the required capability is
     *       in the edge's declared set (let dispatch proceed).</li>
     *   <li>A {@code capability_denied} envelope when the action's
     *       required capability is NOT in {@link Capabilities#DECLARED}.
     *       Envelope carries {@code status=rejected},
     *       {@code error=capability_denied}, and {@code error_details}
     *       naming the offending capability + the declared set, so the
     *       core-side response handler can route the audit row + UI
     *       message without parsing free-text.</li>
     * </ul>
     */
    private Map<String, Object> enforceCapability(
        String action, String routeId, Map<String, Object> cmd
    ) {
        return enforceCapability(
            Capabilities.DECLARED, action, routeId, cmd);
    }

    /**
     * Package-private overload — accepts a custom declared set so the
     * denial path can be exercised in unit tests without rebuilding
     * the image. Production callers go through the no-arg overload.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> enforceCapability(
        java.util.Set<String> declared,
        String action, String routeId, Map<String, Object> cmd
    ) {
        Map<String, Object> params = (Map<String, Object>)
            cmd.getOrDefault("parameters", Collections.emptyMap());
        String required = Capabilities.deriveRequiredCapability(action, params);
        if (required == null) {
            return null;
        }
        if (declared.contains(required)) {
            return null;
        }
        String details = "Edge does not advertise capability '" + required
            + "' for action '" + action + "'. Declared: " + declared + ".";
        return MessageFactory.routeResult(config, routeId,
            "rejected", action, 0, null, null, null,
            "capability_denied", details);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleExecute(String routeId, Map<String, Object> cmd) {
        Map<String, Object> params = (Map<String, Object>)
            cmd.getOrDefault("parameters", Collections.emptyMap());
        String routeYaml = (String) cmd.getOrDefault("route_yaml", "");

        // adr-158 — MCP transport. Keyed on mcp_tool presence; executes a
        // JSON-RPC tool call against an MCP-native system with credentials
        // (mcp_token) local to the controller. Checked before the DB /
        // shell / SQL branches because an MCP command carries none of
        // db_host / shell_command / operation.
        if (McpExecutor.isMcpCommand(params)) {
            return executeMcp(routeId, params);
        }

        // Register DataSource if DB params present
        if (params.containsKey("db_host") || params.containsKey("DB_HOST")) {
            String dbHost = str(params, "db_host", "DB_HOST", "localhost");
            String dbPort = str(params, "db_port", "DB_PORT", "5432");
            String dbName = str(params, "db_name", "DB_NAME", "");
            String dbUser = str(params, "db_user", "DB_USER", "root");
            String dbPass = str(params, "db_pass", "DB_PASS", "");
            dsRegistry.register(routeId, dbHost, dbPort, dbName, dbUser, dbPass);
        }

        // Check for shell command
        String shellCmd = params != null ? (String) params.get("shell_command") : null;
        if (shellCmd == null && routeYaml != null && routeYaml.contains("exec:")) {
            shellCmd = extractShellCommand(routeYaml);
        }

        if (shellCmd != null && !shellCmd.isEmpty()) {
            return executeShell(routeId, shellCmd);
        }

        // Slice 33.1.0 dispatch — parametric vs legacy SQL.
        // ``operation`` keys the only supported path
        // (PreparedStatement, INFORMATION_SCHEMA validation,
        // type-supported gate).
        //
        // security(adr-096): the legacy ``sql`` string-substitution path
        // is RETIRED. A payload carrying a raw ``sql`` key (the former
        // injection surface — unescaped ${param} interpolation executed
        // via a plain Statement) is now rejected with a structured
        // ``unsupported_payload`` envelope instead of being executed. The
        // edge accepts ONLY the parametric ``operation`` payload. We still
        // classify the shape so the rejection message is specific, but no
        // raw-SQL command can reach the database.
        String legacySql = params != null ? (String) params.get("sql") : null;
        if (legacySql == null && routeYaml != null) {
            legacySql = extractQuery(routeYaml);
        }
        PayloadKind kind = classifyPayload(params, legacySql);
        switch (kind) {
            case AMBIGUOUS:
                return MessageFactory.routeResult(config, routeId,
                    "error", null, 0, null, null, null,
                    "Ambiguous payload: both 'operation' (parametric) "
                    + "and 'sql' (legacy) present. Send only 'operation'.",
                    null);
            case MISSING:
                return MessageFactory.routeResult(config, routeId,
                    "error", null, 0, null, null, null,
                    "Missing 'operation' (parametric path) in route "
                    + "parameters", null);
            case PARAMETRIC:
                return executeParametric(routeId, params);
            case LEGACY:
                // security(adr-096): retired raw-SQL path. Fail closed —
                // never route an unescaped ${param}-substituted string to
                // the database. Structured 'unsupported_payload' so core's
                // response handler can audit + surface without parsing
                // free text.
                return MessageFactory.routeResult(config, routeId,
                    "rejected", "query", 0, null, null, null,
                    "unsupported_payload",
                    "Legacy raw 'sql' payloads are no longer accepted. "
                    + "Send a parametric 'operation' "
                    + "(create/update/delete/select/read) payload. "
                    + "The unescaped string-substitution SQL path was "
                    + "retired (adr-096).");
            default:
                throw new IllegalStateException("PayloadKind: " + kind);
        }
    }

    /**
     * Slice 33.1.0 — payload-shape classifier for the dispatch
     * boundary. Package-private so the four-state branching is
     * directly testable without Camel scaffolding.
     */
    enum PayloadKind { PARAMETRIC, LEGACY, AMBIGUOUS, MISSING }

    static PayloadKind classifyPayload(
        Map<String, Object> params, String legacySql
    ) {
        boolean hasOperation = false;
        if (params != null) {
            Object op = params.get("operation");
            if (op instanceof String) {
                hasOperation = !((String) op).isBlank();
            }
        }
        boolean hasSql = legacySql != null && !legacySql.isEmpty();
        if (hasOperation && hasSql) return PayloadKind.AMBIGUOUS;
        if (!hasOperation && !hasSql) return PayloadKind.MISSING;
        return hasOperation ? PayloadKind.PARAMETRIC : PayloadKind.LEGACY;
    }

    /**
     * Slice 33.1.0 — parametric CRUD dispatch via JDBC PreparedStatement.
     * Reads structured payload, delegates to
     * ``SqlExecutor.executeParametric`` which validates table +
     * columns + value types before binding.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> executeParametric(
        String routeId, Map<String, Object> params
    ) {
        DataSource ds = dsRegistry.get(routeId);
        if (ds == null) {
            return MessageFactory.routeResult(config, routeId,
                "error", "execute", 0, null, null, null,
                "No database connection available", null);
        }

        String operation = (String) params.get("operation");

        // ADR-016 F1.T2b — "read" op has a richer payload shape
        // (pattern + filter_column + filter_value / filter_values +
        // exclude_tombstoned) than the write ops or the legacy "select"
        // op. Delegate to executeRead so the dispatch boundary stays
        // operation-aware without forcing the write payload shape onto
        // reads.
        if ("read".equals(operation)) {
            return executeRead(routeId, ds, params);
        }

        String tableName = (String) params.get("table_name");
        Object colsObj = params.get("columns");
        Object valsObj = params.get("values");
        String whereColumn = (String) params.get("where_column");
        Object whereValue = params.get("where_value");

        if (!(colsObj instanceof List) || !(valsObj instanceof List)) {
            return MessageFactory.routeResult(config, routeId,
                "error", "execute", 0, null, null, null,
                "'columns' and 'values' must be present and be lists",
                null);
        }
        List<String> columns = new ArrayList<>();
        for (Object c : (List<Object>) colsObj) {
            columns.add(c == null ? null : c.toString());
        }
        List<Object> values = new ArrayList<>((List<Object>) valsObj);

        Map<String, Object> execResult = SqlExecutor.executeParametric(
            ds, operation, tableName, columns, values,
            whereColumn, whereValue
        );
        String status = (String) execResult.get("status");
        String action = (String) execResult.getOrDefault("action", "execute");
        long latency = ((Number) execResult.get("latency_ms")).longValue();
        Integer rowsAffected = (Integer) execResult.get("rows_affected");
        // Slice 33.1.0b — ``select`` returns row_count + data; writes
        // return rows_affected. Surface both — MessageFactory drops the
        // null fields from the wire envelope.
        Integer rowCount = (Integer) execResult.get("row_count");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data =
            (List<Map<String, Object>>) execResult.get("data");
        String error = (String) execResult.get("error");
        return MessageFactory.routeResult(config, routeId,
            status, action, latency, rowCount, rowsAffected, data, error, null);
    }

    /**
     * ADR-016 F1.T2b — dispatch on-demand record reads to
     * {@link SqlExecutor#executeParametricRead}.
     *
     * Payload carries pattern + filter_column + filter_value /
     * filter_values + exclude_tombstoned alongside the standard
     * parametric envelope. Result shape mirrors the existing
     * ``select`` op (data array + row_count).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> executeRead(
        String routeId, DataSource ds, Map<String, Object> params
    ) {
        Map<String, Object> execResult =
            SqlExecutor.executeParametricRead(ds, params);
        String status = (String) execResult.get("status");
        String action = (String) execResult.getOrDefault("action", "query");
        long latency = ((Number) execResult.get("latency_ms")).longValue();
        Integer rowCount = (Integer) execResult.get("row_count");
        List<Map<String, Object>> data =
            (List<Map<String, Object>>) execResult.get("data");
        String error = (String) execResult.get("error");
        return MessageFactory.routeResult(config, routeId,
            status, action, latency, rowCount, null, data, error, null);
    }

    /**
     * adr-158 — dispatch an MCP tool call to {@link McpExecutor} and wrap
     * its partial result into the standard route-result envelope
     * ({@code action="mcp"}). Credentials (mcp_token) arrive in-band on
     * the route command and never leave the controller.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> executeMcp(String routeId, Map<String, Object> params) {
        Map<String, Object> execResult = mcpExecutor.execute(params);
        String status = (String) execResult.get("status");
        String action = (String) execResult.getOrDefault("action", "mcp");
        long latency = ((Number) execResult.getOrDefault("latency_ms", 0L)).longValue();
        Integer rowCount = (Integer) execResult.get("row_count");
        List<Map<String, Object>> data =
            (List<Map<String, Object>>) execResult.get("data");
        String error = (String) execResult.get("error");
        String errorDetails = (String) execResult.get("error_details");
        return MessageFactory.routeResult(config, routeId,
            status, action, latency, rowCount, null, data, error, errorDetails);
    }

    private Map<String, Object> executeShell(String routeId, String command) {
        if (!ShellExecutor.isAllowed(command)) {
            return MessageFactory.routeResult(config, routeId,
                "rejected", "shell", 0, null, null, null,
                "Command not in whitelist: " + command.split("\\s+")[0], null);
        }
        Map<String, Object> execResult = ShellExecutor.execute(command);
        String status = (String) execResult.get("status");
        long latency = ((Number) execResult.get("latency_ms")).longValue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) execResult.get("data");
        String error = (String) execResult.get("error");
        return MessageFactory.routeResult(config, routeId,
            status, "shell", latency, null, null, data, error, null);
    }

    // security(adr-096): the legacy executeSql(...) helper — which called
    // SqlExecutor.substituteParams (unescaped ${param} interpolation) and
    // then SqlExecutor.execute(ds, rawSql) via a plain Statement — has been
    // removed. The dispatch boundary (handleExecute) now rejects any raw
    // 'sql' payload with an 'unsupported_payload' envelope; only the
    // parametric PreparedStatement path (executeParametric / executeRead)
    // can reach the database.

    /**
     * Phase 14.12 / MTBC.T1 — handle a {@code connect_and_ping} verb.
     *
     * <p>Reads {@code db_host}, {@code db_port}, {@code db_name},
     * {@code db_user}, {@code db_pass} from the command parameters
     * (case-tolerant per existing {@link #str} helper), opens a
     * transient JDBC connection from the edge's <em>runtime context</em>,
     * runs {@code SELECT version(), current_database()}, returns the
     * standard routeResult envelope with the typed reason on failure.
     *
     * <p>Crucially: no side effect. {@link DataSourceRegistry} is NOT
     * touched. The transient {@code DataSource} is closed via
     * try-with-resources inside {@link ConnectionTester#probe} and goes
     * out of scope when this method returns.
     *
     * <p>The probe action on the wire is {@code "connect_and_ping"} so
     * core's response handler can demultiplex from routine reads.
     * Error envelope uses the typed reason (e.g. {@code "host_unresolved"})
     * as the {@code error} field — assistant routes on this without
     * parsing free-text. The raw driver message goes in
     * {@code error_details} for the diagnostic collapsible in the UI.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> handleConnectAndPing(
        String routeId, Map<String, Object> cmd
    ) {
        Map<String, Object> params = (Map<String, Object>)
            cmd.getOrDefault("parameters", Collections.emptyMap());

        String dbHost = str(params, "db_host", "DB_HOST", "");
        String dbPort = str(params, "db_port", "DB_PORT", "5432");
        String dbName = str(params, "db_name", "DB_NAME", "");
        String dbUser = str(params, "db_user", "DB_USER", "");
        String dbPass = str(params, "db_pass", "DB_PASS", "");

        ConnectionTester.ProbeResult probe =
            ConnectionTester.probe(dbHost, dbPort, dbName, dbUser, dbPass);

        if (probe.ok()) {
            return MessageFactory.routeResult(config, routeId,
                "success", "connect_and_ping", probe.latencyMs(),
                null, null, probe.data(), null, null);
        }
        return MessageFactory.routeResult(config, routeId,
            "error", "connect_and_ping", probe.latencyMs(),
            null, null, null, probe.reason(), probe.message());
    }

    private static String str(Map<String, Object> params,
                               String key1, String key2, String def) {
        Object v = params.get(key1);
        if (v == null) v = params.get(key2);
        return v != null ? String.valueOf(v) : def;
    }

    static String extractQuery(String routeYaml) {
        if (routeYaml == null) return null;
        int idx = routeYaml.indexOf("query:");
        if (idx < 0) idx = routeYaml.indexOf("query :");
        if (idx < 0) return null;
        String after = routeYaml.substring(idx + 6).trim();
        if (after.startsWith(">")) after = after.substring(1).trim();
        StringBuilder sb = new StringBuilder();
        for (String line : after.split("\n")) {
            String trimmed = line.trim();
            if (sb.length() > 0 && !trimmed.isEmpty()
                && (trimmed.startsWith("-") || trimmed.contains(":"))) break;
            if (!trimmed.isEmpty()) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(trimmed);
            }
        }
        return sb.toString().trim();
    }

    static String extractShellCommand(String routeYaml) {
        if (routeYaml == null) return null;
        int idx = routeYaml.indexOf("exec:");
        if (idx < 0) return null;
        String after = routeYaml.substring(idx + 5).trim();
        return after.split("\n")[0].trim();
    }
}
