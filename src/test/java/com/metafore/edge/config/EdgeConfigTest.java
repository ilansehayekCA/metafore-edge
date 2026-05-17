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
        // Phase 14.18 — wire-protocol bump (1.1.0 → 1.2.0) signalling
        // the additive `tenants` array on the registration payload.
        // 1.1.0 was the Phase 14.9 / ETA.T2 bump that added runtime +
        // runtime_hints; 1.2.0 adds multi-tenant binding support.
        assertEquals("1.2.0", cfg.edgeVersion());
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

    // ── Phase 14.18 — multi-tenant TENANT_IDS env parsing ─────────────

    @Test
    void tenantsFromTenantIdsCommaList() {
        Map<String, String> env = new HashMap<>();
        env.put("TENANT_IDS", "metafore-corp,metafore-walkthrough");
        EdgeConfig cfg = EdgeConfig.from(env);
        assertEquals(java.util.List.of("metafore-corp", "metafore-walkthrough"),
            cfg.tenants());
    }

    @Test
    void tenantsTrimsAndDropsBlanksAndDedupes() {
        Map<String, String> env = new HashMap<>();
        env.put("TENANT_IDS", "  a , b ,  , a , c , b ,,");
        EdgeConfig cfg = EdgeConfig.from(env);
        // Trim per element, drop blank, dedupe preserving insertion order.
        assertEquals(java.util.List.of("a", "b", "c"), cfg.tenants());
    }

    @Test
    void tenantsBackCompatToLegacyTenantId() {
        // When TENANT_IDS is unset, fall back to [TENANT_ID].
        Map<String, String> env = new HashMap<>();
        env.put("TENANT_ID", "metafore-corp");
        EdgeConfig cfg = EdgeConfig.from(env);
        assertEquals(java.util.List.of("metafore-corp"), cfg.tenants());
    }

    @Test
    void tenantsIgnoresLegacyTenantIdWhenTenantIdsPresent() {
        // TENANT_IDS wins; singleton is not appended.
        Map<String, String> env = new HashMap<>();
        env.put("TENANT_IDS", "a,b");
        env.put("TENANT_ID", "c");  // ignored
        EdgeConfig cfg = EdgeConfig.from(env);
        assertEquals(java.util.List.of("a", "b"), cfg.tenants());
    }

    @Test
    void tenantsEmptyWhenBothEnvsBlank() {
        // Defensive: no TENANT_IDS, no TENANT_ID — but TENANT_ID
        // defaults to "default-tenant" so we'd still get [default-tenant].
        // Test with both forcibly empty.
        Map<String, String> env = new HashMap<>();
        env.put("TENANT_IDS", "");
        env.put("TENANT_ID", "");
        EdgeConfig cfg = EdgeConfig.from(env);
        assertEquals(java.util.List.of(), cfg.tenants());
    }

    @Test
    void tenantsListIsImmutable() {
        Map<String, String> env = new HashMap<>();
        env.put("TENANT_IDS", "a,b");
        EdgeConfig cfg = EdgeConfig.from(env);
        assertThrows(UnsupportedOperationException.class,
            () -> cfg.tenants().add("c"));
    }

    @Test
    void edgeVersionDefaultBumpedTo120() {
        // Phase 14.18 — wire-protocol version bump signals the additive
        // `tenants` array on the registration payload.
        EdgeConfig cfg = EdgeConfig.from(Map.of());
        assertEquals("1.2.0", cfg.edgeVersion());
    }
}
