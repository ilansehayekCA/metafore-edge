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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RegistrationRouteTest extends CamelTestSupport {

    private final EdgeConfig config = EdgeConfig.from(Map.of(
        "CONTROLLER_ID", "test-ctrl",
        "TENANT_ID", "test-tenant",
        "EDGE_VERSION", "1.0.0"
    ));
    private final TopicBuilder topics = new TopicBuilder("test-tenant", "test-ctrl");

    @Override
    public boolean isUseAdviceWith() {
        return true;
    }

    @Override
    protected RoutesBuilder createRouteBuilder() {
        return new RegistrationRoute(config, topics,
            new DataSourceRegistry(context));
    }

    @Test
    @SuppressWarnings("unchecked")
    void registrationFiresOnce() throws Exception {
        AdviceWith.adviceWith(context, "registration", a -> {
            a.weaveByToUri("paho:*").replace().to("mock:registration");
        });
        context.start();

        MockEndpoint mock = getMockEndpoint("mock:registration");
        mock.expectedMessageCount(1);
        mock.assertIsSatisfied(5000);

        String json = mock.getExchanges().get(0).getIn().getBody(String.class);
        Map<String, Object> msg = new ObjectMapper().readValue(json, Map.class);
        assertEquals("test-ctrl", msg.get("controller_id"));
        assertEquals("test-tenant", msg.get("tenant_id"));
        assertEquals("1.0.0", msg.get("version"));
        assertNotNull(msg.get("capabilities"));
        assertTrue(((List<?>) msg.get("capabilities")).contains("shell"));
    }
}
