package com.metafore.edge.service;

import org.apache.camel.CamelContext;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.concurrent.ConcurrentHashMap;

public final class DataSourceRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(DataSourceRegistry.class);

    /**
     * Phase 13 / REK.T6 — default JDBC row fetch size. Safety net against
     * unbounded SELECTs: PG streams rows in this-sized chunks so a missing
     * LIMIT can't OOM the AC JVM. Used when the caller doesn't pass an
     * explicit value (e.g. the legacy two-arg register overload).
     */
    public static final int DEFAULT_ROW_FETCH_SIZE = 500;

    /**
     * Bounded execution timeouts on every managed connection — write-path
     * hang fix.
     *
     * Without these, a parametric INSERT/UPDATE that blocks on a row/table
     * lock calls {@code PreparedStatement.executeUpdate()} with no bound and
     * hangs forever: the edge never publishes a route-result, core's write
     * dispatch waits, and the MCP transport idle-closes the socket
     * ("socket connection closed unexpectedly", 0 rows written). SELECT
     * reads don't contend for write locks, which is exactly why the read
     * path stayed healthy while writes were dead.
     *
     * - {@code statement_timeout} bounds total statement runtime.
     * - {@code lock_timeout} bounds how long a statement waits to ACQUIRE a
     *   lock, so a blocked write fails fast with a typed {@link
     *   java.sql.SQLException} ("canceling statement due to lock timeout")
     *   which {@code SqlExecutor.executeParametric} catches and turns into a
     *   published {@code errorResult} — core gets a clean error instead of a
     *   hang.
     * - {@code socketTimeout} is a network-level backstop set above
     *   {@code statement_timeout} so the server-side timeout fires first
     *   with a clean PG message rather than an opaque socket reset.
     *
     * Values are sized just inside core's {@code crud_camel_timeout_seconds}
     * (30s) so the edge fails within the dispatch window, not after it.
     */
    public static final int STATEMENT_TIMEOUT_MS = 30_000;
    public static final int LOCK_TIMEOUT_MS = 15_000;
    public static final int SOCKET_TIMEOUT_SECONDS = 45;

    private final ConcurrentHashMap<String, DataSource> dataSources = new ConcurrentHashMap<>();
    /** name -> the connection this pool was built for, so an identical
     *  re-registration is skipped and a CHANGED one still replaces. */
    private final ConcurrentHashMap<String, String> fingerprints = new ConcurrentHashMap<>();
    private final CamelContext camelContext;

    public DataSourceRegistry(CamelContext camelContext) {
        this.camelContext = camelContext;
    }

    /**
     * Legacy register overload — delegates to the explicit-fetchSize
     * variant with {@link #DEFAULT_ROW_FETCH_SIZE}. Preserved so call
     * sites that don't carry an EdgeConfig continue to compile.
     */
    public void register(String name, String host, String port,
                         String dbName, String user, String password) {
        register(name, host, port, dbName, user, password, DEFAULT_ROW_FETCH_SIZE);
    }

    /**
     * Phase 13 / REK.T6 — register a PG-backed DataSource with the
     * given {@code defaultRowFetchSize}. Non-positive values fall back
     * to {@link #DEFAULT_ROW_FETCH_SIZE} so a misconfigured caller
     * cannot silently disable the safety net.
     */
    /**
     * The stable name a pool for this connection is registered under.
     *
     * A DataSource depends on WHERE it connects — host, port, database, user —
     * and on nothing about the request that happens to need it. Keying it on the
     * caller's route id meant every read and write minted a pool, because core
     * mints a fresh route id per invocation for correlation
     * ({@code int-<integration>-<operation>-<uuid8>}). Measured on the phluence
     * controller 2026-08-30: 3,121 registrations, 3,121 of them distinct, then
     * {@code OutOfMemoryError: Java heap space} in a 512Mi container. The pod
     * stayed "Running" — only its MQTT threads died — so it silently stopped
     * answering route commands and every delete timed out at 30s.
     *
     * The password is deliberately NOT part of the key: two commands for the
     * same database under the same user are the same connection, and folding a
     * rotated credential into the key would silently mint a second pool rather
     * than surface the rotation.
     */
    public static String connectionKey(String host, String port,
                                       String dbName, String user) {
        return "conn:" + host + ":" + port + ":"
            + (dbName == null ? "" : dbName) + ":"
            + (user == null ? "" : user);
    }

    /** Is a pool already registered under this name? */
    public boolean has(String name) {
        return name != null && dataSources.containsKey(name);
    }

    public void register(String name, String host, String port,
                         String dbName, String user, String password,
                         int defaultRowFetchSize) {
        // IDEMPOTENT ON AN IDENTICAL REGISTRATION, and only that. Re-registering
        // used to build a second PGSimpleDataSource and re-bind it into the
        // Camel registry, which retains it for the life of the process — so a
        // repeated command leaked a pool every time.
        //
        // Skipping is keyed on a fingerprint of the CONNECTION, not merely on
        // the name, because re-register MUST still replace when anything about
        // the connection changes: a re-pointed external source, or a rotated
        // credential, has to take effect rather than keep serving the stale
        // pool. That guarantee is older than this fix and is what
        // DataSourceRegistryExternalSourceTest pins.
        String fingerprint = fingerprint(host, port, dbName, user, password,
                                         defaultRowFetchSize);
        if (fingerprint.equals(fingerprints.get(name))
            && dataSources.containsKey(name)) {
            return;
        }
        try {
            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setServerNames(new String[]{host});
            ds.setPortNumbers(new int[]{Integer.parseInt(port)});
            if (dbName != null && !dbName.isEmpty()) ds.setDatabaseName(dbName);
            if (user != null && !user.isEmpty()) ds.setUser(user);
            if (password != null && !password.isEmpty()) ds.setPassword(password);
            // Phase 13 / REK.T6 — set before binding so any Camel route that
            // immediately uses the data source picks up the chunked fetch.
            int effectiveFetchSize = defaultRowFetchSize > 0
                ? defaultRowFetchSize
                : DEFAULT_ROW_FETCH_SIZE;
            ds.setDefaultRowFetchSize(effectiveFetchSize);
            // Write-path hang fix — bound statement + lock-wait time so a
            // parametric write blocked on a lock fails fast with a typed
            // SQLException (caught -> errorResult -> published to core)
            // instead of hanging executeUpdate() forever. See the field
            // docs above. statement_timeout/lock_timeout are server-side
            // (GUCs via the startup ``options`` packet); socketTimeout is a
            // client-side network backstop.
            ds.setOptions("-c statement_timeout=" + STATEMENT_TIMEOUT_MS
                + " -c lock_timeout=" + LOCK_TIMEOUT_MS);
            ds.setSocketTimeout(SOCKET_TIMEOUT_SECONDS);
            String url = "jdbc:postgresql://" + host + ":" + port
                + (dbName != null && !dbName.isEmpty() ? "/" + dbName : "");
            dataSources.put(name, ds);
            fingerprints.put(name, fingerprint(host, port, dbName, user, password,
                                               effectiveFetchSize));
            camelContext.getRegistry().bind(name, ds);
            LOG.info("DataSource registered: {} -> {} (defaultRowFetchSize={})",
                name, url, effectiveFetchSize);
        } catch (Exception e) {
            LOG.warn("Failed to register DataSource {}: {}", name, e.getMessage());
        }
    }

    public DataSource get(String name) {
        DataSource ds = dataSources.get(name);
        return ds != null ? ds : dataSources.get("default");
    }

    public void remove(String name) {
        dataSources.remove(name);
        fingerprints.remove(name);
    }

    /** How many pools this controller currently holds. Exposed so the leak that
     *  killed the phluence controller is observable rather than only fatal. */
    public int size() {
        return dataSources.size();
    }

    private static String fingerprint(String host, String port, String dbName,
                                      String user, String password, int fetchSize) {
        return String.join("|#|",
            String.valueOf(host), String.valueOf(port), String.valueOf(dbName),
            String.valueOf(user), String.valueOf(password),
            String.valueOf(fetchSize));
    }

    public boolean isDefaultConnected() {
        DataSource ds = dataSources.get("default");
        if (ds == null) return false;
        try (Connection conn = ds.getConnection()) {
            return conn.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }
}
