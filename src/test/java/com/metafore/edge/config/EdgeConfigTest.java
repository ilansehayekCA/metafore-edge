package com.metafore.edge.config;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EdgeConfigTest {

    @Test
    void defaultValues() {
        EdgeConfig cfg = EdgeConfig.from(Map.of());
        assertEquals("edge-default", cfg.controllerId());
        assertEquals("default-tenant", cfg.tenantId());
        assertEquals("tcp://mqtt-broker:1883", cfg.brokerUrl());
        assertEquals("1.0.0", cfg.edgeVersion());
        assertEquals("localhost", cfg.dbHost());
        assertEquals("3306", cfg.dbPort());
        assertEquals("", cfg.dbName());
        assertEquals("root", cfg.dbUser());
        assertEquals("", cfg.dbPass());
        assertEquals("/var/log/monitored/app.log", cfg.logSource());
        assertEquals(30000L, cfg.heartbeatIntervalMs());
        assertEquals(5000L, cfg.discoveryDelayMs());
    }

    @Test
    void overrideValues() {
        Map<String, String> env = new HashMap<>();
        env.put("CONTROLLER_ID", "edge-core-banking");
        env.put("TENANT_ID", "maybank-001");
        env.put("BROKER_URL", "tcp://broker:1884");
        env.put("EDGE_VERSION", "2.0.0");
        env.put("DB_HOST", "db.internal");
        env.put("DB_PORT", "5432");
        env.put("DB_NAME", "core_banking");
        env.put("DB_USER", "admin");
        env.put("DB_PASS", "secret");
        env.put("LOG_SOURCE", "/opt/logs/app.log");
        env.put("HEARTBEAT_INTERVAL_MS", "10000");
        env.put("DISCOVERY_DELAY_MS", "3000");
        EdgeConfig cfg = EdgeConfig.from(env);
        assertEquals("edge-core-banking", cfg.controllerId());
        assertEquals("maybank-001", cfg.tenantId());
        assertEquals("tcp://broker:1884", cfg.brokerUrl());
        assertEquals("2.0.0", cfg.edgeVersion());
        assertEquals("db.internal", cfg.dbHost());
        assertEquals("5432", cfg.dbPort());
        assertEquals("core_banking", cfg.dbName());
        assertEquals("admin", cfg.dbUser());
        assertEquals("secret", cfg.dbPass());
        assertEquals("/opt/logs/app.log", cfg.logSource());
        assertEquals(10000L, cfg.heartbeatIntervalMs());
        assertEquals(3000L, cfg.discoveryDelayMs());
    }
}
