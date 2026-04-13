package com.metafore.edge.topic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TopicBuilderTest {

    private final TopicBuilder topics = new TopicBuilder("maybank-001", "edge-core-banking");

    @Test
    void telemetryRegistration() {
        assertEquals("telemetry/maybank-001/edge-core-banking/registration",
            topics.telemetryRegistration());
    }

    @Test
    void telemetryHeartbeat() {
        assertEquals("telemetry/maybank-001/edge-core-banking/heartbeat",
            topics.telemetryHeartbeat());
    }

    @Test
    void telemetryDiscovery() {
        assertEquals("telemetry/maybank-001/edge-core-banking/discovery",
            topics.telemetryDiscovery());
    }

    @Test
    void telemetryRouteResults() {
        assertEquals("telemetry/maybank-001/edge-core-banking/route-results",
            topics.telemetryRouteResults());
    }

    @Test
    void telemetryEvents() {
        assertEquals("telemetry/maybank-001/edge-core-banking/events",
            topics.telemetryEvents());
    }

    @Test
    void controlRoutes() {
        assertEquals("control/maybank-001/edge-core-banking/routes",
            topics.controlRoutes());
    }

    @Test
    void controlDiscovery() {
        assertEquals("control/maybank-001/edge-core-banking/discovery",
            topics.controlDiscovery());
    }
}
