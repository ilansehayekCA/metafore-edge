package com.metafore.edge.route;

import com.metafore.edge.config.EdgeConfig;
import com.metafore.edge.message.MessageFactory;
import com.metafore.edge.service.DataSourceRegistry;
import com.metafore.edge.topic.TopicBuilder;
import org.apache.camel.builder.RouteBuilder;

import java.util.ArrayList;
import java.util.List;

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
        from("timer:registration?repeatCount=1&delay=2000")
            .routeId("registration")
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

                // Phase 14.9 / ETA.T2 — advertise the auto-detected
                // edge runtime + frozen diagnostic hints map alongside
                // the existing db_* fields. Both are OPTIONAL on the
                // schema so older cores ignore them.
                exchange.getIn().setBody(MessageFactory.registration(
                    config, capabilities, dbType, dbHost, dbPort,
                    config.runtime(), config.runtimeHints()));
            })
            .marshal().json()
            .to("paho:" + topics.telemetryRegistration()
                + "?brokerUrl=" + config.brokerUrl())
            .log("Registration published");
    }
}
