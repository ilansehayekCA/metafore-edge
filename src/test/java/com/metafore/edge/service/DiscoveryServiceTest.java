package com.metafore.edge.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DiscoveryServiceTest {

    @Test
    void defaultScopeHasExpectedKeys() {
        Map<String, Object> scope = DiscoveryService.defaultScope();
        assertEquals(true, scope.get("discover_os"));
        assertEquals(true, scope.get("discover_ports"));
        assertEquals(true, scope.get("discover_processes"));
        assertEquals(false, scope.get("discover_docker"));
        assertNotNull(scope.get("log_dirs"));
        assertNotNull(scope.get("connectivity_targets"));
        assertNotNull(scope.get("databases"));
    }

    @Test
    void discoverOSReturnsSuccess() {
        Map<String, Object> result = DiscoveryService.discoverOS("test-host");
        assertEquals("success", result.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("test-host", data.get("hostname"));
        assertNotNull(data.get("os"));
        assertNotNull(data.get("cpu_cores"));
    }

    @Test
    void skippedStatus() {
        Map<String, Object> result = DiscoveryService.skipped();
        assertEquals("skipped", result.get("status"));
        assertNull(result.get("data"));
        assertNull(result.get("error"));
    }

    @Test
    void successWrapsData() {
        Map<String, Object> result = DiscoveryService.success("test-data");
        assertEquals("success", result.get("status"));
        assertEquals("test-data", result.get("data"));
    }

    @Test
    void failedWrapsError() {
        Map<String, Object> result = DiscoveryService.failed("something broke");
        assertEquals("failed", result.get("status"));
        assertEquals("something broke", result.get("error"));
    }

    @Test
    void labelPortKnownPorts() {
        assertEquals("ssh", DiscoveryService.labelPort(22));
        assertEquals("http", DiscoveryService.labelPort(80));
        assertEquals("mqtt", DiscoveryService.labelPort(1883));
        assertEquals("mysql", DiscoveryService.labelPort(3306));
        assertEquals("postgresql", DiscoveryService.labelPort(5432));
    }

    @Test
    void labelPortUnknown() {
        assertEquals("tcp/9999", DiscoveryService.labelPort(9999));
    }

    @Test
    void executeWithSkippedScope() {
        Map<String, Object> scope = Map.of(
            "discover_os", false,
            "discover_ports", false,
            "discover_processes", false
        );
        Map<String, Object> caps = DiscoveryService.execute("test-ctrl", scope);
        assertEquals("skipped", ((Map<?, ?>) caps.get("os")).get("status"));
        assertEquals("skipped", ((Map<?, ?>) caps.get("ports")).get("status"));
        assertEquals("skipped", ((Map<?, ?>) caps.get("processes")).get("status"));
    }
}
