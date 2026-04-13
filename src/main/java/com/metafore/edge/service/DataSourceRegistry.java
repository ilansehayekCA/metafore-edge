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

    private final ConcurrentHashMap<String, DataSource> dataSources = new ConcurrentHashMap<>();
    private final CamelContext camelContext;

    public DataSourceRegistry(CamelContext camelContext) {
        this.camelContext = camelContext;
    }

    public void register(String name, String host, String port,
                         String dbName, String user, String password) {
        try {
            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setServerNames(new String[]{host});
            ds.setPortNumbers(new int[]{Integer.parseInt(port)});
            if (dbName != null && !dbName.isEmpty()) ds.setDatabaseName(dbName);
            if (user != null && !user.isEmpty()) ds.setUser(user);
            if (password != null && !password.isEmpty()) ds.setPassword(password);
            String url = "jdbc:postgresql://" + host + ":" + port
                + (dbName != null && !dbName.isEmpty() ? "/" + dbName : "");
            dataSources.put(name, ds);
            camelContext.getRegistry().bind(name, ds);
            LOG.info("DataSource registered: {} -> {}", name, url);
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
