package com.metafore.edge.route;

import com.metafore.edge.config.EdgeConfig;
import com.metafore.edge.message.MessageFactory;
import com.metafore.edge.service.DiscoveryService;
import com.metafore.edge.topic.TopicBuilder;
import org.apache.camel.builder.RouteBuilder;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

public class DiscoveryRoute extends RouteBuilder {

    private final EdgeConfig config;
    private final TopicBuilder topics;

    public DiscoveryRoute(EdgeConfig config, TopicBuilder topics) {
        this.config = config;
        this.topics = topics;
    }

    @Override
    public void configure() {
        // Startup discovery (one-shot)
        from("timer:discovery?repeatCount=1&delay=" + config.discoveryDelayMs())
            .routeId("discovery-startup")
            .process(exchange -> {
                Map<String, Object> scope = DiscoveryService.defaultScope();
                Map<String, Object> capabilities =
                    DiscoveryService.execute(config.controllerId(), scope);
                exchange.getIn().setBody(MessageFactory.discoveryResult(
                    config, UUID.randomUUID().toString(), "startup", capabilities));
            })
            .marshal().json()
            .to("paho:" + topics.telemetryDiscovery()
                + "?brokerUrl=" + config.brokerUrl())
            .log("Startup discovery published");

        // On-demand discovery (from Core)
        from("paho:" + topics.controlDiscovery()
            + "?brokerUrl=" + config.brokerUrl())
            .routeId("discovery-ondemand")
            .log("Discovery command received")
            .unmarshal().json(Map.class)
            .process(exchange -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> cmd = exchange.getIn().getBody(Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> scope = (Map<String, Object>)
                    cmd.getOrDefault("scope", DiscoveryService.defaultScope());
                String trigger = (String) cmd.getOrDefault("trigger", "remote");
                Map<String, Object> capabilities =
                    DiscoveryService.execute(config.controllerId(), scope);
                exchange.getIn().setBody(MessageFactory.discoveryResult(
                    config, UUID.randomUUID().toString(), trigger, capabilities));
            })
            .marshal().json()
            .to("paho:" + topics.telemetryDiscovery()
                + "?brokerUrl=" + config.brokerUrl())
            .log("Discovery results published");
    }
}
