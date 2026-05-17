package com.metafore.edge.route;

import com.metafore.edge.config.EdgeConfig;
import com.metafore.edge.message.MessageFactory;
import com.metafore.edge.service.DataSourceRegistry;
import com.metafore.edge.topic.TopicBuilder;
import org.apache.camel.builder.RouteBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 14.18 — Multi-tenant registration publisher.
 *
 * <p>Constructed once per tenant: {@link com.metafore.edge.AccessController}
 * iterates {@code config.tenants()} and instantiates one
 * {@code RegistrationRoute} per tenant with that tenant's
 * {@link TopicBuilder}. Each instance publishes its own registration
 * message to {@code telemetry/<tenant>/<cid>/registration}. The payload
 * carries the FULL tenants list (not just this tenant's slug) on the
 * new {@code tenants} field, so core's {@code _handle_registration}
 * writes one HAS_CONTROLLER edge per tenant on receipt of a SINGLE
 * registration message. Sending one registration per tenant also
 * means every tenant's core has at least one registration to bind on
 * even if the broker silently drops one of the messages.
 *
 * <p>Routes are uniquely named with the tenant slug suffix
 * ({@code registration-<slug>}) so they can be independently observed
 * in Camel diagnostics.
 */
public class RegistrationRoute extends RouteBuilder {

    private final EdgeConfig config;
    private final TopicBuilder topics;
    private final DataSourceRegistry dsRegistry;

    public RegistrationRoute(EdgeConfig config, TopicBuilder topics,
                             DataSourceRegistry dsRegistry) {
        this.config = config;
        this.topics = topics;
        this.dsRegistry = dsRegistry;
    }

    @Override
    public void configure() {
        // Phase 14.18 — routeId suffixed with the tenant slug from the
        // builder so each per-tenant RegistrationRoute is independently
        // visible in Camel's lifecycle hooks + JMX. Legacy single-
        // tenant edges still see ``registration-<tenant>`` (the singleton
        // tenant slug); behavior unchanged.
        String routeId = "registration-" + topics.tenantId();
        from("timer:" + routeId + "?repeatCount=1&delay=2000")
            .routeId(routeId)
            .process(exchange -> {
                List<String> capabilities = new ArrayList<>();
                capabilities.add("shell");
                if (dsRegistry.isDefaultConnected()) {
                    capabilities.add("jdbc");
                }

                String dbType = dsRegistry.isDefaultConnected() ? "postgresql" : "none";
                String dbHost = dsRegistry.isDefaultConnected() ? config.dbHost() : null;
                Integer dbPort = dsRegistry.isDefaultConnected()
                    ? Integer.parseInt(config.dbPort()) : null;

                // Phase 14.9 / ETA.T2 — runtime + runtime_hints
                // Phase 14.18 — tenants list (all tenants this physical
                //   edge serves; this route fires once per tenant but
                //   each payload carries the full set so core can write
                //   N HAS_CONTROLLER edges on a single registration).
                exchange.getIn().setBody(MessageFactory.registration(
                    config, capabilities, dbType, dbHost, dbPort,
                    config.runtime(), config.runtimeHints(),
                    config.tenants()));
            })
            .marshal().json()
            .to("paho:" + topics.telemetryRegistration()
                + "?brokerUrl=" + config.brokerUrl())
            .log("Registration published for tenant=" + topics.tenantId());
    }
}
