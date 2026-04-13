package com.metafore.edge.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metafore.edge.config.EdgeConfig;
import com.metafore.edge.service.DataSourceRegistry;
import com.metafore.edge.topic.TopicBuilder;
import org.apache.camel.RoutesBuilder;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.test.junit5.CamelTestSupport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HeartbeatRouteTest extends CamelTestSupport {

    private final EdgeConfig config = EdgeConfig.from(Map.of(
        "CONTROLLER_ID", "test-ctrl",
        "TENANT_ID", "test-tenant",
        "HEARTBEAT_INTERVAL_MS", "100"
    ));
    private final TopicBuilder topics = new TopicBuilder("test-tenant", "test-ctrl");

    @Override
    public boolean isUseAdviceWith() {
        return true;
    }

    @Override
    protected RoutesBuilder createRouteBuilder() {
        return new HeartbeatRoute(config, topics,
            new DataSourceRegistry(context), Instant.now());
    }

    @Test
    @SuppressWarnings("unchecked")
    void heartbeatProducesValidMessage() throws Exception {
        AdviceWith.adviceWith(context, "heartbeat", a -> {
            a.weaveByToUri("paho:*").replace().to("mock:heartbeat");
        });
        context.start();

        MockEndpoint mock = getMockEndpoint("mock:heartbeat");
        mock.expectedMinimumMessageCount(1);
        mock.assertIsSatisfied(5000);

        String json = mock.getExchanges().get(0).getIn().getBody(String.class);
        Map<String, Object> msg = new ObjectMapper().readValue(json, Map.class);
        assertEquals("test-ctrl", msg.get("controller_id"));
        assertEquals("test-tenant", msg.get("tenant_id"));
        assertEquals("active", msg.get("status"));
        assertNotNull(msg.get("timestamp"));
        assertNotNull(msg.get("uptime_seconds"));
    }
}
