package com.metafore.edge.topic;

/**
 * Builds MQTT topic strings for a (tenant, controller) pair.
 *
 * <p>Phase 14.18 — when an edge serves N tenants from one physical
 * process, callers construct one {@code TopicBuilder} per tenant
 * (same {@code controllerId}, different {@code tenantId}). All
 * topic strings remain tenant-scoped: telemetry fans out one publisher
 * per tenant, control fans out one subscription per tenant. The
 * tenant_id segment of the path remains the security boundary
 * (broker-side ACL enforces who can subscribe to which tenant —
 * tracked separately as W-024).
 */
public final class TopicBuilder {

    private final String tenantId;
    private final String controllerId;

    public TopicBuilder(String tenantId, String controllerId) {
        this.tenantId = tenantId;
        this.controllerId = controllerId;
    }

    /** Phase 14.18 — slug-scoped accessor used by route IDs / log
     *  lines so a multi-tenant edge can report which tenant a route
     *  belongs to in diagnostics. */
    public String tenantId()         { return tenantId; }
    public String controllerId()     { return controllerId; }

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
