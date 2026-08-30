package com.metafore.edge.service;

import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pool per DATABASE, not a pool per request.
 *
 * WHAT HAPPENED. Core mints a fresh route id per invocation so it can correlate
 * the result back to the request — {@code int-<integration>-<operation>-<uuid8>}.
 * The edge registered a DataSource under that id and bound it into the Camel
 * registry, which retains it for the life of the process. Nothing ever unbound
 * it, and {@code remove()} only dropped the local map entry.
 *
 * Measured on the phluence controller, 2026-08-30:
 *
 * <pre>
 *   DataSource registrations : 3,121
 *   distinct route ids       : 3,121   — every one unique
 *   OutOfMemoryError         : 21
 *   container memory limit   : 512Mi
 * </pre>
 *
 * The pod stayed {@code Running} because its main thread survived; only the MQTT
 * client threads died. So Kubernetes reported it healthy while it silently
 * stopped answering route commands, and every delete failed with "controller
 * did not respond within 30.0s".
 */
class DataSourceRegistryLeakTest {

    private static final String HOST = "postgresql";
    private static final String PORT = "5432";
    private static final String DB = "phluence";
    private static final String USER = "metafore";
    private static final String PASS = "metafore123";

    private DataSourceRegistry newRegistry() {
        CamelContext ctx = new DefaultCamelContext();
        return new DataSourceRegistry(ctx);
    }

    /** THE DEFECT, as a test. A thousand commands against one database. */
    @Test
    void athousandCommandsAgainstOneDatabaseHoldOnePool() {
        DataSourceRegistry registry = newRegistry();
        String key = DataSourceRegistry.connectionKey(HOST, PORT, DB, USER);

        for (int i = 0; i < 1_000; i++) {
            registry.register(key, HOST, PORT, DB, USER, PASS);
        }

        assertEquals(1, registry.size(),
            "one database must hold one pool however many commands ask for it");
    }

    /** The same pool OBJECT, not merely the same count — a replacement would
     *  still leak the old one into the Camel registry. */
    @Test
    void anIdenticalReregistrationReusesTheSameInstance() {
        DataSourceRegistry registry = newRegistry();
        String key = DataSourceRegistry.connectionKey(HOST, PORT, DB, USER);

        registry.register(key, HOST, PORT, DB, USER, PASS);
        DataSource first = registry.get(key);
        registry.register(key, HOST, PORT, DB, USER, PASS);
        DataSource second = registry.get(key);

        assertSame(first, second, "an identical re-registration rebuilt the pool");
    }

    /** Different databases are different pools — the fix must not collapse them. */
    @Test
    void differentConnectionsKeepTheirOwnPools() {
        DataSourceRegistry registry = newRegistry();

        registry.register(DataSourceRegistry.connectionKey(HOST, PORT, "db_a", USER),
            HOST, PORT, "db_a", USER, PASS);
        registry.register(DataSourceRegistry.connectionKey(HOST, PORT, "db_b", USER),
            HOST, PORT, "db_b", USER, PASS);
        registry.register(DataSourceRegistry.connectionKey("other-host", PORT, "db_a", USER),
            "other-host", PORT, "db_a", USER, PASS);
        registry.register(DataSourceRegistry.connectionKey(HOST, PORT, "db_a", "other-user"),
            HOST, PORT, "db_a", "other-user", PASS);

        assertEquals(4, registry.size(),
            "host, database and user each identify a distinct connection");
    }

    /**
     * A ROTATED CREDENTIAL MUST TAKE EFFECT. The key deliberately omits the
     * password — two commands for the same database under the same user are the
     * same connection — so the skip is keyed on the full connection fingerprint
     * instead. Otherwise a rotation would keep serving a pool that can no longer
     * authenticate, which fails as a hang rather than an error.
     */
    @Test
    void aChangedPasswordReplacesThePool() {
        DataSourceRegistry registry = newRegistry();
        String key = DataSourceRegistry.connectionKey(HOST, PORT, DB, USER);

        registry.register(key, HOST, PORT, DB, USER, "old-password");
        DataSource first = registry.get(key);
        registry.register(key, HOST, PORT, DB, USER, "new-password");
        DataSource second = registry.get(key);

        assertNotNull(second);
        assertTrue(first != second, "a rotated credential kept the stale pool");
        assertEquals(1, registry.size(), "replacing must not leave both behind");
    }

    /** The pre-existing guarantee, restated here so the leak fix cannot quietly
     *  regress it: a re-pointed external source takes effect. */
    @Test
    void arePointedSourceStillReplaces() {
        DataSourceRegistry registry = newRegistry();
        String key = "ext-stable-id-read_count-abcdef01";

        registry.register(key, "host-v1", PORT, "db1", "u", "p");
        registry.register(key, "host-v2", PORT, "db1", "u", "p");

        PGSimpleDataSource ds = (PGSimpleDataSource) registry.get(key);
        assertEquals("host-v2", ds.getServerNames()[0]);
        assertEquals(1, registry.size());
    }

    /** remove() must clear the fingerprint too, or a later re-register is
     *  skipped against a pool that is no longer there. */
    @Test
    void removeThenRegisterRebuildsThePool() {
        DataSourceRegistry registry = newRegistry();
        String key = DataSourceRegistry.connectionKey(HOST, PORT, DB, USER);

        registry.register(key, HOST, PORT, DB, USER, PASS);
        registry.remove(key);
        assertEquals(0, registry.size());

        registry.register(key, HOST, PORT, DB, USER, PASS);
        assertEquals(1, registry.size());
        assertNotNull(registry.get(key));
    }
}
