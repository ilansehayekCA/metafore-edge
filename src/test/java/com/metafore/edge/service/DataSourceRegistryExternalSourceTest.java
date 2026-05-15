package com.metafore.edge.service;

import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Phase 14 / APE.T3 — postgres_external dynamic per-Source
 * DataSource registration contract test.
 *
 * <p>The brief calls out "edge dynamic DataSource registration"
 * (option (a)) as the work this task closes. Investigation determined
 * the existing {@link DataSourceRegistry#register(String, String, String,
 * String, String, String)} overload + {@link com.metafore.edge.route.RouteExecutorRoute}'s
 * per-call ``db_host``-based registration is already the right shape
 * for the postgres_external adapter — no Java change required. This
 * test locks that contract:
 *
 * <ul>
 *   <li>Different ``route_id`` keys produce isolated DataSources
 *       (so two external Sources on the same AC don't trample each
 *       other's host/port/db/user/password).</li>
 *   <li>Re-registering the same ``route_id`` replaces the previous
 *       binding (each dispatch is its own short-lived registration;
 *       no leak across dispatches against the same Source).</li>
 *   <li>``get`` falls back to the ``default`` DataSource only when
 *       the requested key is absent — the AC's pre-existing safety
 *       net stays intact for managed_pg dispatches that don't carry
 *       db_host params.</li>
 *   <li>``remove`` cleanly unbinds; subsequent ``get`` falls back to
 *       default.</li>
 * </ul>
 *
 * <p>Tests do NOT need a live Postgres — they exercise the registry
 * contract directly. The full external-PG round-trip is covered by
 * the metafore-core adapter test suite + an integration lifecycle
 * test that runs against a live laptop PG fixture.
 */
class DataSourceRegistryExternalSourceTest {

    private static DataSourceRegistry newRegistry() {
        CamelContext ctx = new DefaultCamelContext();
        return new DataSourceRegistry(ctx);
    }

    @Test
    void distinctRouteIdsProduceIsolatedDataSources() {
        // Two external Sources reachable via the same AC should never
        // share a DataSource instance — host/port/db/user/password
        // are per-Source, so the AC keys them by route_id.
        DataSourceRegistry registry = newRegistry();

        registry.register(
            "ext-src-customer-a-read_by_id-aaaaaaaa",
            "customer-a.db.example.com", "5432", "warehouse",
            "metafore_ro", "secretA"
        );
        registry.register(
            "ext-src-customer-b-read_by_id-bbbbbbbb",
            "customer-b.internal", "5433", "operational",
            "metafore_ro", "secretB"
        );

        DataSource a = registry.get("ext-src-customer-a-read_by_id-aaaaaaaa");
        DataSource b = registry.get("ext-src-customer-b-read_by_id-bbbbbbbb");
        assertNotNull(a);
        assertNotNull(b);
        assertNotSame(a, b, "per-Source DataSources must be isolated");

        // Verify the embedded connection params differ — this is the
        // observable contract that backs cross-Source isolation.
        PGSimpleDataSource pgA = (PGSimpleDataSource) a;
        PGSimpleDataSource pgB = (PGSimpleDataSource) b;
        assertEquals("customer-a.db.example.com", pgA.getServerNames()[0]);
        assertEquals("customer-b.internal", pgB.getServerNames()[0]);
        assertEquals(5432, pgA.getPortNumbers()[0]);
        assertEquals(5433, pgB.getPortNumbers()[0]);
        assertEquals("warehouse", pgA.getDatabaseName());
        assertEquals("operational", pgB.getDatabaseName());
    }

    @Test
    void reRegisterSameRouteIdReplacesBinding() {
        // Each MQTT dispatch builds a fresh route_id (uuid suffix in
        // _publish_via_camel) so collisions are unlikely in practice.
        // But the registry MUST replace on re-register so a stale
        // DataSource can never leak across an Edge restart -> client
        // reconnection sequence.
        DataSourceRegistry registry = newRegistry();
        String key = "ext-stable-id-read_count-abcdef01";

        registry.register(key, "host-v1", "5432", "db1", "u", "p");
        DataSource first = registry.get(key);
        assertEquals("host-v1", ((PGSimpleDataSource) first).getServerNames()[0]);

        registry.register(key, "host-v2", "5432", "db1", "u", "p");
        DataSource second = registry.get(key);
        assertEquals("host-v2", ((PGSimpleDataSource) second).getServerNames()[0]);
    }

    @Test
    void getFallsBackToDefaultForUnknownKey() {
        DataSourceRegistry registry = newRegistry();
        registry.register("default", "managed-pg", "5432", "metafore_default",
            "metafore", "managedPw");

        DataSource looked = registry.get("nonexistent-route-id");
        DataSource def = registry.get("default");
        assertSame(def, looked,
            "unknown keys must fall back to the default DataSource so "
            + "managed_pg dispatches without db_host params still work");
    }

    @Test
    void getReturnsNullWhenNoDefaultAndUnknownKey() {
        // Defensive: with no default and an unknown key, get() returns
        // null. The dispatcher (or RouteExecutorRoute) treats null as
        // "no database connection available".
        DataSourceRegistry registry = newRegistry();
        assertNull(registry.get("anything"));
    }

    @Test
    void removeUnbindsSoSubsequentGetFallsBackToDefault() {
        DataSourceRegistry registry = newRegistry();
        registry.register("default", "managed-pg", "5432", "metafore_default",
            "metafore", "managedPw");
        registry.register("ext-src-temp", "external-pg", "5432", "pharma",
            "u", "p");

        // Verify isolation before removal.
        DataSource ext = registry.get("ext-src-temp");
        assertEquals("external-pg",
            ((PGSimpleDataSource) ext).getServerNames()[0]);

        registry.remove("ext-src-temp");
        DataSource after = registry.get("ext-src-temp");
        DataSource def = registry.get("default");
        assertSame(def, after,
            "after remove, lookup must fall back to default");
    }

    @Test
    void serviceAccountUserNamePassesThroughIntact() {
        // postgres_external's manifest accepts service_account connection
        // type — the user value can be long (e.g. a service-account
        // email or a token-bound principal). Verify the username makes
        // it through the registration without truncation.
        DataSourceRegistry registry = newRegistry();
        String sa = "metafore-ro@customer-a-prod.iam.gserviceaccount.com";
        registry.register("ext-with-sa", "h", "5432", "d", sa, "pw");

        PGSimpleDataSource pg = (PGSimpleDataSource) registry.get("ext-with-sa");
        assertEquals(sa, pg.getUser());
    }
}
