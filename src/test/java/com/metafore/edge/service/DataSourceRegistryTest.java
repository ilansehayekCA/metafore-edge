package com.metafore.edge.service;

import com.metafore.edge.config.EdgeConfig;
import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 13 / REK.T6 — JDBC {@code defaultRowFetchSize} safety net.
 *
 * Verifies the {@link DataSourceRegistry} sets a non-zero fetch size on
 * the underlying {@link PGSimpleDataSource} so unbounded SELECTs stream
 * in bounded chunks rather than materializing the whole result set on
 * the AC JVM heap. Tests do not need a live Postgres connection — the
 * fetch-size property is plumbed through PG JDBC's {@code BaseDataSource}
 * and is observable without opening a socket.
 */
class DataSourceRegistryTest {

    private static final String HOST = "localhost";
    private static final String PORT = "5432";
    private static final String DB   = "metafore_test";
    private static final String USER = "metafore";
    private static final String PASS = "test";

    @Test
    void registerDefaultsTo500WhenLegacyOverloadUsed() {
        CamelContext ctx = new DefaultCamelContext();
        DataSourceRegistry registry = new DataSourceRegistry(ctx);

        registry.register("default", HOST, PORT, DB, USER, PASS);

        PGSimpleDataSource pg = asPg(registry.get("default"));
        assertEquals(500, pg.getDefaultRowFetchSize(),
            "legacy register overload must default fetchSize to 500");
    }

    @Test
    void registerHonoursExplicitFetchSize() {
        CamelContext ctx = new DefaultCamelContext();
        DataSourceRegistry registry = new DataSourceRegistry(ctx);

        registry.register("custom", HOST, PORT, DB, USER, PASS, 1000);

        PGSimpleDataSource pg = asPg(registry.get("custom"));
        assertEquals(1000, pg.getDefaultRowFetchSize());
    }

    @Test
    void registerClampsNonPositiveFetchSizeToDefault() {
        CamelContext ctx = new DefaultCamelContext();
        DataSourceRegistry registry = new DataSourceRegistry(ctx);

        registry.register("zero", HOST, PORT, DB, USER, PASS, 0);
        registry.register("negative", HOST, PORT, DB, USER, PASS, -42);

        assertEquals(500, asPg(registry.get("zero")).getDefaultRowFetchSize(),
            "fetchSize=0 must not silently disable the safety net");
        assertEquals(500, asPg(registry.get("negative")).getDefaultRowFetchSize(),
            "negative fetchSize must clamp to the default");
    }

    @Test
    void registerBindsToCamelRegistry() {
        CamelContext ctx = new DefaultCamelContext();
        DataSourceRegistry registry = new DataSourceRegistry(ctx);

        registry.register("default", HOST, PORT, DB, USER, PASS, 500);

        DataSource bound = ctx.getRegistry().lookupByNameAndType(
            "default", DataSource.class);
        assertNotNull(bound, "DataSource must be bound to Camel registry");
        assertTrue(bound instanceof PGSimpleDataSource);
        assertEquals(500, ((PGSimpleDataSource) bound).getDefaultRowFetchSize());
    }

    @Test
    void defaultRowFetchSizeConstantIs500() {
        // Locks the contract — Brief 11 / REK.T6 specifies 500 as the v1
        // default. Bumping this is fine but must be a conscious change.
        assertEquals(500, DataSourceRegistry.DEFAULT_ROW_FETCH_SIZE);
    }

    // ── EdgeConfig wiring ───────────────────────────────────────────

    @Test
    void edgeConfigDefaultsRowFetchSizeTo500WhenEnvMissing() {
        EdgeConfig cfg = EdgeConfig.from(new HashMap<>());
        assertEquals(500, cfg.defaultRowFetchSize());
    }

    @Test
    void edgeConfigHonoursDefaultRowFetchSizeEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("DEFAULT_ROW_FETCH_SIZE", "1000");
        EdgeConfig cfg = EdgeConfig.from(env);
        assertEquals(1000, cfg.defaultRowFetchSize());
    }

    @Test
    void edgeConfigFallsBackToDefaultOnGarbageEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("DEFAULT_ROW_FETCH_SIZE", "not-a-number");
        EdgeConfig cfg = EdgeConfig.from(env);
        assertEquals(500, cfg.defaultRowFetchSize(),
            "garbage env value must fall back to 500, not blow up startup");
    }

    @Test
    void edgeConfigClampsNonPositiveEnvToDefault() {
        Map<String, String> env = new HashMap<>();
        env.put("DEFAULT_ROW_FETCH_SIZE", "0");
        EdgeConfig cfg = EdgeConfig.from(env);
        assertEquals(500, cfg.defaultRowFetchSize(),
            "fetchSize=0 from env must not disable the safety net");

        env.put("DEFAULT_ROW_FETCH_SIZE", "-1");
        cfg = EdgeConfig.from(env);
        assertEquals(500, cfg.defaultRowFetchSize());
    }

    // ── helpers ─────────────────────────────────────────────────────

    private static PGSimpleDataSource asPg(DataSource ds) {
        assertNotNull(ds, "DataSource must not be null");
        assertTrue(ds instanceof PGSimpleDataSource,
            "expected PGSimpleDataSource, got " + ds.getClass());
        return (PGSimpleDataSource) ds;
    }
}
