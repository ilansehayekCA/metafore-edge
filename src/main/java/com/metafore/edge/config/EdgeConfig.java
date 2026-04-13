package com.metafore.edge.config;

import java.util.Map;

public final class EdgeConfig {

    private final String controllerId;
    private final String tenantId;
    private final String brokerUrl;
    private final String edgeVersion;
    private final String dbHost;
    private final String dbPort;
    private final String dbName;
    private final String dbUser;
    private final String dbPass;
    private final String logSource;
    private final long heartbeatIntervalMs;
    private final long discoveryDelayMs;

    private EdgeConfig(Map<String, String> env) {
        this.controllerId       = env.getOrDefault("CONTROLLER_ID", "edge-default");
        this.tenantId           = env.getOrDefault("TENANT_ID", "default-tenant");
        this.brokerUrl          = env.getOrDefault("BROKER_URL", "tcp://mqtt-broker:1883");
        this.edgeVersion        = env.getOrDefault("EDGE_VERSION", "1.0.0");
        this.dbHost             = env.getOrDefault("DB_HOST", "localhost");
        this.dbPort             = env.getOrDefault("DB_PORT", "5432");
        this.dbName             = env.getOrDefault("DB_NAME", "");
        this.dbUser             = env.getOrDefault("DB_USER", "root");
        this.dbPass             = env.getOrDefault("DB_PASS", "");
        this.logSource          = env.getOrDefault("LOG_SOURCE", "/var/log/monitored/app.log");
        this.heartbeatIntervalMs = Long.parseLong(env.getOrDefault("HEARTBEAT_INTERVAL_MS", "30000"));
        this.discoveryDelayMs   = Long.parseLong(env.getOrDefault("DISCOVERY_DELAY_MS", "5000"));
    }

    public static EdgeConfig fromSystem() {
        return new EdgeConfig(System.getenv());
    }

    public static EdgeConfig from(Map<String, String> env) {
        return new EdgeConfig(env);
    }

    public String controllerId()       { return controllerId; }
    public String tenantId()           { return tenantId; }
    public String brokerUrl()          { return brokerUrl; }
    public String edgeVersion()        { return edgeVersion; }
    public String dbHost()             { return dbHost; }
    public String dbPort()             { return dbPort; }
    public String dbName()             { return dbName; }
    public String dbUser()             { return dbUser; }
    public String dbPass()             { return dbPass; }
    public String logSource()          { return logSource; }
    public long heartbeatIntervalMs()  { return heartbeatIntervalMs; }
    public long discoveryDelayMs()     { return discoveryDelayMs; }
}
