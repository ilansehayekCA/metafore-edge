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
    public void register(String name, String host, String port,
                         String dbName, String user, String password,
                         int defaultRowFetchSize) {
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
