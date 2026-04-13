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

    public static Map<String, Object> registration(EdgeConfig config,
            List<String> capabilities, String dbType, String dbHost, Integer dbPort) {
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
