package com.metafore.edge.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metafore.edge.config.EdgeConfig;
import com.networknt.schema.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MessageFactoryTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static EdgeConfig config;

    @BeforeAll
    static void setUp() {
        config = EdgeConfig.from(Map.of(
            "CONTROLLER_ID", "edge-core-banking",
            "TENANT_ID", "maybank-001",
            "EDGE_VERSION", "1.0.0"
        ));
    }

    @Test
    void heartbeatMatchesSchema() throws Exception {
        Map<String, Object> msg = MessageFactory.heartbeat(
            config, "active", 3600, 5, 3, true,
            Map.of("threads_connected", 4, "slow_queries", 0, "uptime", 86400));
        validate(msg, "heartbeat.schema.json");
    }

    @Test
    void heartbeatMinimalMatchesSchema() throws Exception {
        Map<String, Object> msg = MessageFactory.heartbeat(
            config, "degraded", 0, 0, 0, false, null);
        validate(msg, "heartbeat.schema.json");
    }

    @Test
    void registrationMatchesSchema() throws Exception {
        Map<String, Object> msg = MessageFactory.registration(
            config, List.of("jdbc", "shell"), "mariadb", "localhost", 3306);
        validate(msg, "registration.schema.json");
    }

    @Test
    void registrationMinimalMatchesSchema() throws Exception {
        Map<String, Object> msg = MessageFactory.registration(
            config, List.of("jdbc"), null, null, null);
        validate(msg, "registration.schema.json");
    }

    // ── Phase 14.9 / ETA.T2 — runtime + runtime_hints additive fields ──

    @Test
    void registrationLegacyOverloadOmitsRuntime() {
        // Back-compat invariant: the 5-arg overload must produce a
        // payload identical-in-shape to pre-14.9. Older edges that
        // never call the 7-arg form ship payloads without runtime; the
        // schema still validates them.
        Map<String, Object> msg = MessageFactory.registration(
            config, List.of("jdbc"), null, null, null);
        assertFalse(msg.containsKey("runtime"));
        assertFalse(msg.containsKey("runtime_hints"));
    }

    @Test
    void registrationWithRuntimeEmitsField() throws Exception {
        Map<String, Object> hints = new LinkedHashMap<>();
        hints.put("os_name", "Windows 11");
        hints.put("java_version", "21.0.2");
        hints.put("hostname", "laptop-001");
        hints.put("docker_env_file_present", false);
        hints.put("cgroup_signature", "unavailable");
        Map<String, Object> msg = MessageFactory.registration(
            config, List.of("jdbc"), "postgresql", "localhost", 5432,
            "native", hints);
        assertEquals("native", msg.get("runtime"));
        assertEquals(hints, msg.get("runtime_hints"));
        validate(msg, "registration.schema.json");
    }

    @Test
    void registrationWithRuntimeDockerHostNetworkValidates() throws Exception {
        Map<String, Object> msg = MessageFactory.registration(
            config, List.of("jdbc"), "postgresql", "localhost", 5432,
            "docker-host-network", Map.of("os_name", "Linux"));
        assertEquals("docker-host-network", msg.get("runtime"));
        validate(msg, "registration.schema.json");
    }

    @Test
    void registrationWithUnknownRuntimeValidates() throws Exception {
        Map<String, Object> msg = MessageFactory.registration(
            config, List.of("jdbc"), null, null, null,
            "unknown", null);
        assertEquals("unknown", msg.get("runtime"));
        // null/empty hints map is omitted from the wire payload.
        assertFalse(msg.containsKey("runtime_hints"));
        validate(msg, "registration.schema.json");
    }

    @Test
    void registrationWithBlankRuntimeOmitsField() {
        Map<String, Object> msg = MessageFactory.registration(
            config, List.of("jdbc"), null, null, null,
            "   ", null);
        assertFalse(msg.containsKey("runtime"));
    }

    @Test
    void registrationWithEmptyHintsOmitsField() {
        Map<String, Object> msg = MessageFactory.registration(
            config, List.of("jdbc"), null, null, null,
            "docker", Map.of());
        assertFalse(msg.containsKey("runtime_hints"));
    }

    @Test
    void eventMatchesSchema() throws Exception {
        Map<String, Object> msg = MessageFactory.event(
            config, "error", "database", "/var/log/app.log", "Connection refused");
        validate(msg, "event.schema.json");
    }

    @Test
    void eventMinimalMatchesSchema() throws Exception {
        Map<String, Object> msg = MessageFactory.event(
            config, "info", null, null, null);
        validate(msg, "event.schema.json");
    }

    @Test
    void routeResultSuccessMatchesSchema() throws Exception {
        List<Map<String, Object>> data = List.of(
            Map.of("id", 1, "name", "test"));
        Map<String, Object> msg = MessageFactory.routeResult(
            config, "route-001", "success", "query", 42,
            1, null, data, null, null);
        validate(msg, "route_result.schema.json");
    }

    @Test
    void routeResultErrorMatchesSchema() throws Exception {
        Map<String, Object> msg = MessageFactory.routeResult(
            config, "route-002", "error", "execute", 10,
            null, null, null, "Table not found", "SQL error details");
        validate(msg, "route_result.schema.json");
    }

    @Test
    void routeResultRejectedMatchesSchema() throws Exception {
        Map<String, Object> msg = MessageFactory.routeResult(
            config, "route-003", "rejected", null, 0,
            null, null, null, "Query not in whitelist", null);
        validate(msg, "route_result.schema.json");
    }

    @Test
    void writeBackResultSuccessMatchesSchema() throws Exception {
        Map<String, Object> msg = MessageFactory.writeBackResult(
            config, "wb-001", "success", 200,
            "{\"id\":\"INC001\",\"state\":\"resolved\"}",
            Map.of("state", "open"),
            Map.of("state", "resolved"));
        validate(msg, "write_back_result.schema.json");
    }

    @Test
    void writeBackResultErrorMatchesSchema() throws Exception {
        Map<String, Object> msg = MessageFactory.writeBackResult(
            config, "wb-002", "error", 401,
            "Unauthorized", null, null);
        validate(msg, "write_back_result.schema.json");
    }

    @Test
    void writeBackResultMinimalMatchesSchema() throws Exception {
        Map<String, Object> msg = MessageFactory.writeBackResult(
            config, "wb-003", "error", 0,
            "Exception: Connection refused", null, null);
        validate(msg, "write_back_result.schema.json");
    }

    @Test
    void discoveryResultMatchesSchema() throws Exception {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("os", Map.of("status", "success", "data",
            Map.of("hostname", "edge-vm-01", "os", "Linux 5.15")));
        capabilities.put("ports", Map.of("status", "skipped"));
        Map<String, Object> msg = MessageFactory.discoveryResult(
            config, UUID.randomUUID().toString(), "startup", capabilities);
        validate(msg, "discovery_result.schema.json");
    }

    @Test
    void discoveryResultMinimalMatchesSchema() throws Exception {
        Map<String, Object> msg = MessageFactory.discoveryResult(
            config, UUID.randomUUID().toString(), "remote", null);
        validate(msg, "discovery_result.schema.json");
    }

    @Test
    void heartbeatUsesControllerIdNotAgentId() throws Exception {
        Map<String, Object> msg = MessageFactory.heartbeat(
            config, "active", 0, 0, 0, false, null);
        assertTrue(msg.containsKey("controller_id"));
        assertTrue(msg.containsKey("tenant_id"));
        assertFalse(msg.containsKey("agent_id"));
        assertFalse(msg.containsKey("site"));
    }

    private void validate(Map<String, Object> message, String schemaFile) throws Exception {
        JsonNode jsonNode = mapper.valueToTree(message);
        InputStream schemaStream = getClass().getResourceAsStream("/schemas/" + schemaFile);
        assertNotNull(schemaStream, "Schema file not found: " + schemaFile);
        ObjectNode schemaNode = (ObjectNode) mapper.readTree(schemaStream);
        // Remove non-URI $id that causes strict validation errors
        schemaNode.remove("$id");
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        JsonSchema schema = factory.getSchema(schemaNode);
        Set<ValidationMessage> errors = schema.validate(jsonNode);
        assertTrue(errors.isEmpty(),
            "Schema validation failed for " + schemaFile + ": " + errors);
    }
}
