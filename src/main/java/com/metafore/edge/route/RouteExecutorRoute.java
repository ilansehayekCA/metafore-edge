package com.metafore.edge.route;

import com.metafore.edge.config.EdgeConfig;
import com.metafore.edge.message.MessageFactory;
import com.metafore.edge.service.DataSourceRegistry;
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

    public RouteExecutorRoute(EdgeConfig config, TopicBuilder topics,
                              DataSourceRegistry dsRegistry) {
        this.config = config;
        this.topics = topics;
        this.dsRegistry = dsRegistry;
    }

    @Override
    public void configure() {
        from("paho:" + topics.controlRoutes() + "?brokerUrl=" + config.brokerUrl())
            .routeId("route-executor")
            .log("Route command received: ${body}")
            .unmarshal().json(Map.class)
            .process(exchange -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> cmd = exchange.getIn().getBody(Map.class);
                String action = (String) cmd.getOrDefault("action", "");
                String routeId = (String) cmd.getOrDefault("route_id", "");

                Map<String, Object> result;

                if ("deploy".equals(action) || "execute".equals(action)) {
                    result = handleExecute(routeId, cmd);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleExecute(String routeId, Map<String, Object> cmd) {
        Map<String, Object> params = (Map<String, Object>)
            cmd.getOrDefault("parameters", Collections.emptyMap());
        String routeYaml = (String) cmd.getOrDefault("route_yaml", "");

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
        // ``operation`` keys the new parametric path
        // (PreparedStatement, INFORMATION_SCHEMA validation,
        // type-supported gate). ``sql`` keys the legacy
        // string-substitution path (kept for rolling-window
        // compatibility with cores that haven't flipped yet).
        // Explicit handling for both ambiguous and missing
        // payloads — defense-in-depth at the dispatch boundary.
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
                    + "and 'sql' (legacy) present. Send exactly one.",
                    null);
            case MISSING:
                return MessageFactory.routeResult(config, routeId,
                    "error", null, 0, null, null, null,
                    "Missing 'operation' (parametric path) or 'sql' "
                    + "(legacy path) in route parameters", null);
            case PARAMETRIC:
                return executeParametric(routeId, params);
            case LEGACY:
                return executeSql(routeId, legacySql, params);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeSql(String routeId, String sql,
                                            Map<String, Object> params) {
        sql = SqlExecutor.substituteParams(sql, params);

        if (!SqlExecutor.isAllowed(sql)) {
            return MessageFactory.routeResult(config, routeId,
                "rejected", "query", 0, null, null, null,
                "Query not in whitelist: " + sql.substring(0, Math.min(30, sql.length())), null);
        }

        DataSource ds = dsRegistry.get(routeId);
        if (ds == null) {
            return MessageFactory.routeResult(config, routeId,
                "error", "query", 0, null, null, null,
                "No database connection available", null);
        }

        Map<String, Object> execResult = SqlExecutor.execute(ds, sql);
        String status = (String) execResult.get("status");
        String action = (String) execResult.get("action");
        long latency = ((Number) execResult.get("latency_ms")).longValue();
        Integer rowCount = (Integer) execResult.get("row_count");
        Integer rowsAffected = (Integer) execResult.get("rows_affected");
        List<Map<String, Object>> data = (List<Map<String, Object>>) execResult.get("data");
        String error = (String) execResult.get("error");
        return MessageFactory.routeResult(config, routeId,
            status, action, latency, rowCount, rowsAffected, data, error, null);
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
