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
        // Phase 14.18 — routeId is suffixed with the tenant slug.
        AdviceWith.adviceWith(context, "registration-test-tenant", a -> {
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
        List<?> caps = (List<?>) msg.get("capabilities");
        // CCA.T2 / compass ba53231a — canonical capability names
        // (replaces legacy [shell] + [jdbc] advertisement).
        assertTrue(caps.contains("shell"));
        assertTrue(caps.contains("test_connection"),
            "CCA.T2 — edge must advertise test_connection so the "
            + "platform preflight can route connect_and_ping commands");
        assertTrue(caps.contains("read"));
        assertTrue(caps.contains("write"));
        assertTrue(caps.contains("introspect"));
        assertTrue(caps.contains("healthcheck"));
        assertTrue(caps.contains("ddl"));
        // Legacy transport-flavor strings must not leak.
        assertFalse(caps.contains("jdbc"),
            "CCA.T2 — legacy 'jdbc' advertisement must not leak; the "
            + "platform preflight keys on canonical capability names");
        // Phase 14.18 — single-tenant config — `tenants` carries
        // [tenant_id] from EdgeConfig back-compat path.
        assertEquals(List.of("test-tenant"), msg.get("tenants"));
    }
}
