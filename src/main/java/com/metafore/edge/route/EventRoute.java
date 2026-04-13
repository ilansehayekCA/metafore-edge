package com.metafore.edge.route;

import com.metafore.edge.config.EdgeConfig;
import com.metafore.edge.message.MessageFactory;
import com.metafore.edge.topic.TopicBuilder;
import org.apache.camel.builder.RouteBuilder;

public class EventRoute extends RouteBuilder {

    private final EdgeConfig config;
    private final TopicBuilder topics;

    public EventRoute(EdgeConfig config, TopicBuilder topics) {
        this.config = config;
        this.topics = topics;
    }

    @Override
    public void configure() {
        from("stream:file?fileName=" + config.logSource()
            + "&scanStream=true&scanStreamDelay=1000"
            + "&retry=true&fileWatcher=true")
            .routeId("event-log-tail")
            .filter(body().isNotNull())
            .filter(body().regex(".*(INFO|ERROR|WARN|CRITICAL).*"))
            .process(exchange -> {
                String line = exchange.getIn().getBody(String.class);
                if (line == null || line.trim().isEmpty()) {
                    exchange.setRouteStop(true);
                    return;
                }
                String severity =
                    line.contains("CRITICAL") ? "critical" :
                    line.contains("ERROR")    ? "error" :
                    line.contains("WARN")     ? "warn" : "info";
                exchange.getIn().setBody(MessageFactory.event(
                    config, severity, null, config.logSource(), line.trim()));
            })
            .marshal().json()
            .to("paho:" + topics.telemetryEvents()
                + "?brokerUrl=" + config.brokerUrl())
            .log("Event published");
    }
}
