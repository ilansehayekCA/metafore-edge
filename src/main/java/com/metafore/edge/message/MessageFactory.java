package com.metafore.edge.message;

import com.metafore.edge.config.EdgeConfig;

import java.time.Instant;
import java.util.*;

public final class MessageFactory {

    private MessageFactory() {}

    public static Map<String, Object> heartbeat(EdgeConfig config, String status,
            long uptimeSeconds, int routesDeployed, int routesActive,
            boolean dbConnected, Map<String, Object> dbStats) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("controller_id", config.controllerId());
        msg.put("tenant_id", config.tenantId());
        msg.put("timestamp", Instant.now().toString());
        msg.put("status", status);
        msg.put("uptime_seconds", uptimeSeconds);
        msg.put("routes_deployed", routesDeployed);
        msg.put("routes_active", routesActive);
        msg.put("db_connected", dbConnected);
        if (dbStats != null) {
            msg.put("db_stats", dbStats);
        }
        return msg;
    }

    /**
     * Backward-compat 5-arg overload. Calls the 8-arg variant with
     * {@code runtime=null}, {@code runtimeHints=null}, {@code tenants=null}
     * — emitting a payload that older Phase-13 schemas validated
     * (no runtime field, no tenants array). Production callers should
     * use the 8-arg form; this overload exists so test fixtures + any
     * legacy call sites continue to compile without churn.
     */
    public static Map<String, Object> registration(EdgeConfig config,
            List<String> capabilities, String dbType, String dbHost, Integer dbPort) {
        return registration(config, capabilities, dbType, dbHost, dbPort,
            null, null, null);
    }

    /**
     * Phase 14.9 / ETA.T2 backward-compat 7-arg overload (no tenants).
     * Calls the 8-arg variant with {@code tenants=null} — emits the
     * pre-14.18 payload shape (singleton {@code tenant_id} only, no
     * {@code tenants} array). Kept so existing call sites that only
     * care about runtime advertisement compile without change.
     */
    public static Map<String, Object> registration(EdgeConfig config,
            List<String> capabilities, String dbType, String dbHost, Integer dbPort,
            String runtime, Map<String, Object> runtimeHints) {
        return registration(config, capabilities, dbType, dbHost, dbPort,
            runtime, runtimeHints, null);
    }

    /**
     * Phase 14.18 — extended registration payload composer with
     * {@code tenants} array support.
     *
     * <p>Wire fields (in order):
     * <ul>
     *   <li>{@code controller_id}, {@code tenant_id} (singleton —
     *       legacy single-tenant edges + back-compat for cores that
     *       ignore the {@code tenants} array)</li>
     *   <li>{@code timestamp}, {@code version}, {@code capabilities}</li>
     *   <li>{@code db_type}, {@code db_host}, {@code db_port},
     *       {@code db_name} (when set)</li>
     *   <li>{@code runtime}, {@code runtime_hints} (Phase 14.9 —
     *       omitted when null/blank/empty)</li>
     *   <li>{@code tenants: [slug,...]} (Phase 14.18 — omitted when
     *       null/empty; emitted only when the edge declares
     *       {@code TENANT_IDS=a,b,...}). When present, core's
     *       {@code _handle_registration} writes one
     *       {@code (:Tenant)-[:HAS_CONTROLLER]->(:AccessController)}
     *       edge per tenant slug. Bindings are additive.</li>
     * </ul>
     *
     * <p>All fields except controller_id / tenant_id / timestamp /
     * version / capabilities are OPTIONAL on the schema (see
     * {@code contracts/mqtt/registration.schema.json}) so older cores
     * ignore unknown fields.
     */
    public static Map<String, Object> registration(EdgeConfig config,
            List<String> capabilities, String dbType, String dbHost, Integer dbPort,
            String runtime, Map<String, Object> runtimeHints,
            List<String> tenants) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("controller_id", config.controllerId());
        msg.put("tenant_id", config.tenantId());
        msg.put("timestamp", Instant.now().toString());
        msg.put("version", config.edgeVersion());
        msg.put("capabilities", capabilities);
        if (dbType != null) {
            msg.put("db_type", dbType);
        }
        if (dbHost != null) {
            msg.put("db_host", dbHost);
        }
        if (dbPort != null) {
            msg.put("db_port", dbPort);
        }
        String dbName = config.dbName();
        if (dbName != null && !dbName.isEmpty()) {
            msg.put("db_name", dbName);
        }
        if (runtime != null && !runtime.isBlank()) {
            msg.put("runtime", runtime);
        }
        if (runtimeHints != null && !runtimeHints.isEmpty()) {
            msg.put("runtime_hints", runtimeHints);
        }
        if (tenants != null && !tenants.isEmpty()) {
            // Phase 14.18 — Defensive copy: caller may pass an
            // unmodifiable view from EdgeConfig#tenants(). Serialization
            // doesn't mutate, but downstream tests inspect msg directly,
            // so emit a plain LinkedHashList equivalent.
            msg.put("tenants", new java.util.ArrayList<>(tenants));
        }
        return msg;
    }

    public static Map<String, Object> event(EdgeConfig config, String severity,
            String component, String logSource, String rawMessage) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("event_id", UUID.randomUUID().toString());
        msg.put("controller_id", config.controllerId());
        msg.put("tenant_id", config.tenantId());
        msg.put("timestamp", Instant.now().toString());
        msg.put("severity", severity);
        if (component != null) {
            msg.put("component", component);
        }
        if (logSource != null) {
            msg.put("log_source", logSource);
        }
        if (rawMessage != null) {
            msg.put("raw_message", rawMessage);
        }
        return msg;
    }

    public static Map<String, Object> routeResult(EdgeConfig config, String routeId,
            String status, String action, long latencyMs,
            Integer rowCount, Integer rowsAffected, List<Map<String, Object>> data,
            String error, String errorDetails) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("controller_id", config.controllerId());
        msg.put("tenant_id", config.tenantId());
        msg.put("route_id", routeId);
        msg.put("timestamp", Instant.now().toString());
        msg.put("status", status);
        if (action != null) {
            msg.put("action", action);
        }
        msg.put("latency_ms", latencyMs);
        if (rowCount != null) {
            msg.put("row_count", rowCount);
        }
        if (rowsAffected != null) {
            msg.put("rows_affected", rowsAffected);
        }
        if (data != null) {
            msg.put("data", data);
        }
        if (error != null) {
            msg.put("error", error);
        }
        if (errorDetails != null) {
            msg.put("error_details", errorDetails);
        }
        return msg;
    }

    public static Map<String, Object> writeBackResult(EdgeConfig config,
            String actionId, String status, int httpStatus, String responseBody,
            Map<String, Object> oldValues, Map<String, Object> newValues) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("controller_id", config.controllerId());
        msg.put("tenant_id", config.tenantId());
        msg.put("action_id", actionId);
        msg.put("timestamp", Instant.now().toString());
        msg.put("status", status);
        msg.put("http_status", httpStatus);
        if (responseBody != null) {
            msg.put("response_body", responseBody);
        }
        if (oldValues != null) {
            msg.put("old_values", oldValues);
        }
        if (newValues != null) {
            msg.put("new_values", newValues);
        }
        return msg;
    }

    public static Map<String, Object> discoveryResult(EdgeConfig config,
            String runId, String trigger, Map<String, Object> capabilities) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("controller_id", config.controllerId());
        msg.put("tenant_id", config.tenantId());
        msg.put("timestamp", Instant.now().toString());
        msg.put("run_id", runId);
        msg.put("trigger", trigger);
        if (capabilities != null) {
            msg.put("capabilities", capabilities);
        }
        return msg;
    }
}
