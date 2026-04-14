package com.metafore.edge.topic;

public final class TopicBuilder {

    private final String tenantId;
    private final String controllerId;

    public TopicBuilder(String tenantId, String controllerId) {
        this.tenantId = tenantId;
        this.controllerId = controllerId;
    }

    // Access Controller -> Core (telemetry)
    public String telemetryRegistration() { return telemetry("registration"); }
    public String telemetryHeartbeat()    { return telemetry("heartbeat"); }
    public String telemetryDiscovery()    { return telemetry("discovery"); }
    public String telemetryRouteResults() { return telemetry("route-results"); }
    public String telemetryEvents()       { return telemetry("events"); }

    // Core -> Access Controller (control)
    public String controlRoutes()    { return control("routes"); }
    public String controlDiscovery() { return control("discovery"); }
    public String controlWriteBack() { return control("write-back"); }

    private String telemetry(String suffix) {
        return "telemetry/" + tenantId + "/" + controllerId + "/" + suffix;
    }

    private String control(String suffix) {
        return "control/" + tenantId + "/" + controllerId + "/" + suffix;
    }
}
