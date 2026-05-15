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
