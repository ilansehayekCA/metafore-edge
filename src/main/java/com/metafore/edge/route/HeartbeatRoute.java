package com.metafore.edge.route;

import com.metafore.edge.config.EdgeConfig;
import com.metafore.edge.message.MessageFactory;
import com.metafore.edge.service.DataSourceRegistry;
import com.metafore.edge.topic.TopicBuilder;
import org.apache.camel.builder.RouteBuilder;

import java.time.Instant;

public class HeartbeatRoute extends RouteBuilder {

    private final EdgeConfig config;
    private final TopicBuilder topics;
    private final DataSourceRegistry dsRegistry;
    private final Instant startTime;

    public HeartbeatRoute(EdgeConfig config, TopicBuilder topics,
                          DataSourceRegistry dsRegistry, Instant startTime) {
        this.config = config;
        this.topics = topics;
        this.dsRegistry = dsRegistry;
        this.startTime = startTime;
    }

    @Override
    public void configure() {
        from("timer:heartbeat?period=" + config.heartbeatIntervalMs())
            .routeId("heartbeat")
            .process(exchange -> {
                long uptimeSeconds = Instant.now().getEpochSecond() - startTime.getEpochSecond();
                int routeCount = exchange.getContext().getRoutes().size();
                boolean dbConnected = dsRegistry.isDefaultConnected();
                exchange.getIn().setBody(MessageFactory.heartbeat(
                    config, "active", uptimeSeconds, routeCount, routeCount,
                    dbConnected, null));
            })
            .marshal().json()
            .to("paho:" + topics.telemetryHeartbeat()
                + "?brokerUrl=" + config.brokerUrl())
            .log("Heartbeat sent");
    }
}
