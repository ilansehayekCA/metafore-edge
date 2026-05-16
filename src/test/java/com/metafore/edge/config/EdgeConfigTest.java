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
        // Phase 14.9 / ETA.T2 — wire-protocol bump signalling the
        // additive runtime + runtime_hints registration payload fields.
        assertEquals("1.1.0", cfg.edgeVersion());
        assertEquals("localhost", cfg.dbHost());
        // Slice 33.1.0 fixture fix: EdgeConfig.dbPort defaults to 5432
        // (PostgreSQL) since the 2026-04-12 MariaDB→PostgreSQL migration.
        // Test fixture was stale at 3306 (MariaDB); rot was hidden by
        // the disabled-in-CI test suite. Aligning to current production
        // default. Matches metafore-edge/CLAUDE.md gotcha #4.
        assertEquals("5432", cfg.dbPort());
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

    @Test
    void runtimeExposedAsWireString() {
        // Phase 14.9 / ETA.T1 — explicit override travels through to
        // the accessor as the kebab-case wire string.
        Map<String, String> env = new HashMap<>();
        env.put("EDGE_RUNTIME", "docker");
        // Inject empty Probes so the override branch is the only
        // active signal — keeps the test independent of the host
        // platform's actual cgroup / .dockerenv layout.
        EdgeConfig cfg = EdgeConfig.from(env, new RuntimeProbe.Probes(
            env::get, k -> null, p -> false, p -> null
        ));
        assertEquals("docker", cfg.runtime());
    }

    @Test
    void runtimeFallsThroughToUnknownWhenAllSignalsEmpty() {
        Map<String, String> env = new HashMap<>();
        EdgeConfig cfg = EdgeConfig.from(env, new RuntimeProbe.Probes(
            k -> null, k -> null, p -> false, p -> null
        ));
        assertEquals("unknown", cfg.runtime());
    }

    @Test
    void runtimeHintsAlwaysCarryFiveKeys() {
        Map<String, String> env = new HashMap<>();
        EdgeConfig cfg = EdgeConfig.from(env, new RuntimeProbe.Probes(
            k -> null, k -> null, p -> false, p -> null
        ));
        Map<String, Object> hints = cfg.runtimeHints();
        assertEquals(5, hints.size());
        assertTrue(hints.containsKey("os_name"));
        assertTrue(hints.containsKey("java_version"));
        assertTrue(hints.containsKey("hostname"));
        assertTrue(hints.containsKey("docker_env_file_present"));
        assertTrue(hints.containsKey("cgroup_signature"));
    }
}
