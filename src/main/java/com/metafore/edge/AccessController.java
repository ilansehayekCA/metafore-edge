package com.metafore.edge;

import com.metafore.edge.config.EdgeConfig;
import com.metafore.edge.route.*;
import com.metafore.edge.service.DataSourceRegistry;
import com.metafore.edge.topic.TopicBuilder;
import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

public class AccessController {

    private static final Logger LOG = LoggerFactory.getLogger(AccessController.class);

    public static void main(String[] args) throws Exception {
        EdgeConfig config = EdgeConfig.fromSystem();
        Instant startTime = Instant.now();

        // Phase 14.18 — N tenants per physical edge process. When
        // TENANT_IDS is unset, config.tenants() falls back to a
        // single-element list with the singleton TENANT_ID, preserving
        // pre-1.2.0 behavior. Empty list (both env vars blank) is
        // tolerated — we just skip MQTT route construction so the
        // process can still boot for shape diagnostics.
        java.util.List<String> tenantSlugs = config.tenants();
        LOG.info(
            "Starting Access Controller: {} runtime={} serving {} tenant(s): {}",
            config.controllerId(),
            config.runtime(),
            tenantSlugs.size(),
            tenantSlugs);
        if (tenantSlugs.isEmpty()) {
            LOG.warn(
                "No tenants declared (TENANT_IDS and TENANT_ID both blank). "
                + "Skipping MQTT route construction; process will idle. "
                + "Set TENANT_IDS=tenant_a,tenant_b,... to enable multi-tenant "
                + "serving from one process.");
        }

        CamelContext context = new DefaultCamelContext();
        DataSourceRegistry dsRegistry = new DataSourceRegistry(context);

        // Register default DataSource if DB is available
        if (!config.dbHost().isEmpty()) {
            try {
                // Phase 13 / REK.T6 — explicit defaultRowFetchSize from
                // EdgeConfig (overridable via DEFAULT_ROW_FETCH_SIZE env).
                dsRegistry.register("default",
                    config.dbHost(), config.dbPort(), config.dbName(),
                    config.dbUser(), config.dbPass(),
                    config.defaultRowFetchSize());
                LOG.info("Default DataSource registered");
            } catch (Exception e) {
                LOG.info("No local database available (OK for gateway mode)");
            }
        }

        // Phase 14.18 — one HeartbeatRoute is shared across all tenants
        // (heartbeats use the primary tenant slug — first declared in
        // TENANT_IDS — for back-compat with core's per-tenant heartbeat
        // bucket. The multi-tenant binding lives on AccessController
        // node properties, not on the heartbeat itself).
        String primaryTenant = tenantSlugs.isEmpty()
            ? config.tenantId() : tenantSlugs.get(0);
        TopicBuilder primaryTopics = new TopicBuilder(
            primaryTenant, config.controllerId());
        context.addRoutes(new HeartbeatRoute(
            config, primaryTopics, dsRegistry, startTime));

        // Phase 14.18 — per-tenant control subscriptions + registration
        // publishers. Each tenant gets its own RouteExecutorRoute and
        // RegistrationRoute instance with a tenant-scoped TopicBuilder,
        // producing routeIds like ``route-executor-metafore-corp`` and
        // ``registration-metafore-corp``. Existing single-tenant
        // deployments hit this same code path with a 1-element list,
        // so the topology degrades cleanly.
        for (String slug : tenantSlugs) {
            TopicBuilder tenantTopics = new TopicBuilder(
                slug, config.controllerId());
            context.addRoutes(new RegistrationRoute(
                config, tenantTopics, dsRegistry));
            context.addRoutes(new DiscoveryRoute(config, tenantTopics));
            context.addRoutes(new RouteExecutorRoute(
                config, tenantTopics, dsRegistry));
            context.addRoutes(new EventRoute(config, tenantTopics));
            context.addRoutes(new RestAdapterRoute(config, tenantTopics));
        }

        context.start();
        LOG.info(
            "Access Controller started — {} routes active across {} tenant(s)",
            context.getRoutes().size(),
            tenantSlugs.size());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                context.stop();
            } catch (Exception e) {
                LOG.error("Error during shutdown", e);
            }
        }));
        Thread.currentThread().join();
    }
}
