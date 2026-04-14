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
        TopicBuilder topics = new TopicBuilder(config.tenantId(), config.controllerId());
        Instant startTime = Instant.now();

        LOG.info("Starting Access Controller: {} (tenant={})",
            config.controllerId(), config.tenantId());

        CamelContext context = new DefaultCamelContext();
        DataSourceRegistry dsRegistry = new DataSourceRegistry(context);

        // Register default DataSource if DB is available
        if (!config.dbHost().isEmpty()) {
            try {
                dsRegistry.register("default",
                    config.dbHost(), config.dbPort(), config.dbName(),
                    config.dbUser(), config.dbPass());
                LOG.info("Default DataSource registered");
            } catch (Exception e) {
                LOG.info("No local database available (OK for gateway mode)");
            }
        }

        // Add routes
        context.addRoutes(new HeartbeatRoute(config, topics, dsRegistry, startTime));
        context.addRoutes(new RegistrationRoute(config, topics, dsRegistry));
        context.addRoutes(new DiscoveryRoute(config, topics));
        context.addRoutes(new RouteExecutorRoute(config, topics, dsRegistry));
        context.addRoutes(new EventRoute(config, topics));
        context.addRoutes(new RestAdapterRoute(config, topics));

        context.start();
        LOG.info("Access Controller started — {} routes active", context.getRoutes().size());

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
