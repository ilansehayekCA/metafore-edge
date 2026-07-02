package com.metafore.edge.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CCA.T2 / compass ba53231a — coverage of the canonical capability set
 * + the action→capability derivation. Pure-function tests, no Camel.
 */
class CapabilitiesTest {

    @Test
    void declaredSetCoversEveryCanonicalNameThisEdgeServes() {
        // Matches metafore-core's KNOWN_AC_CAPABILITIES minus 'sync'
        // (Equilibrium method E sync is platform-side, not on edge).
        assertTrue(Capabilities.DECLARED.contains("shell"));
        assertTrue(Capabilities.DECLARED.contains("test_connection"));
        assertTrue(Capabilities.DECLARED.contains("healthcheck"));
        assertTrue(Capabilities.DECLARED.contains("introspect"));
        assertTrue(Capabilities.DECLARED.contains("read"));
        assertTrue(Capabilities.DECLARED.contains("write"));
        assertTrue(Capabilities.DECLARED.contains("ddl"));
        assertTrue(Capabilities.DECLARED.contains("mcp"));  // adr-158
        assertFalse(Capabilities.DECLARED.contains("sync"));
        // Legacy transport-flavor strings must not leak.
        assertFalse(Capabilities.DECLARED.contains("jdbc"));
    }

    @Test
    void declaredListMatchesDeclaredSet() {
        assertEquals(
            Capabilities.DECLARED.size(),
            Capabilities.declaredList().size()
        );
        for (String cap : Capabilities.declaredList()) {
            assertTrue(Capabilities.DECLARED.contains(cap));
        }
    }

    @Test
    void connectAndPingMapsToTestConnection() {
        assertEquals(
            "test_connection",
            Capabilities.deriveRequiredCapability("connect_and_ping", null)
        );
        assertEquals(
            "test_connection",
            Capabilities.deriveRequiredCapability("connect_and_ping", new HashMap<>())
        );
    }

    @Test
    void removeMapsToWrite() {
        // 'remove' mutates the on-edge DataSourceRegistry — counts as write.
        assertEquals(
            "write",
            Capabilities.deriveRequiredCapability("remove", null)
        );
    }

    @Test
    void parametricReadMapsToRead() {
        Map<String, Object> p = new HashMap<>();
        p.put("operation", "read");
        assertEquals(
            "read",
            Capabilities.deriveRequiredCapability("execute", p)
        );
    }

    @Test
    void parametricSelectMapsToRead() {
        // Legacy parametric 'select' op aliases to read.
        Map<String, Object> p = new HashMap<>();
        p.put("operation", "select");
        assertEquals(
            "read",
            Capabilities.deriveRequiredCapability("execute", p)
        );
    }

    @Test
    void parametricCreateUpdateDeleteMapToWrite() {
        for (String op : new String[]{"create", "update", "delete"}) {
            Map<String, Object> p = new HashMap<>();
            p.put("operation", op);
            assertEquals(
                "write",
                Capabilities.deriveRequiredCapability("execute", p),
                "operation=" + op
            );
        }
    }

    @Test
    void mcpToolMapsToMcp() {
        // adr-158 — mcp_tool presence selects the MCP transport, taking
        // precedence over shell/operation classification.
        Map<String, Object> p = new HashMap<>();
        p.put("mcp_tool", "get_roadmap");
        assertEquals(
            "mcp",
            Capabilities.deriveRequiredCapability("execute", p)
        );
        assertEquals(
            "mcp",
            Capabilities.deriveRequiredCapability("deploy", p)
        );
    }

    @Test
    void blankMcpToolFallsThroughToWrite() {
        Map<String, Object> p = new HashMap<>();
        p.put("mcp_tool", "  ");
        assertEquals(
            "write",
            Capabilities.deriveRequiredCapability("execute", p)
        );
    }

    @Test
    void shellCommandMapsToShell() {
        Map<String, Object> p = new HashMap<>();
        p.put("shell_command", "df -h");
        assertEquals(
            "shell",
            Capabilities.deriveRequiredCapability("execute", p)
        );
    }

    @Test
    void routeYamlWithExecMapsToShell() {
        Map<String, Object> p = new HashMap<>();
        p.put("route_yaml", "name: t\nexec: ps aux\n");
        assertEquals(
            "shell",
            Capabilities.deriveRequiredCapability("execute", p)
        );
    }

    @Test
    void legacySqlPathMapsToWrite() {
        // Coarse upper-bound: legacy SQL execute without an 'operation'
        // field could be SELECT or INSERT — capability-level enforcement
        // treats it as write so a read-only edge can't accept legacy SQL
        // at all. SqlExecutor's whitelist gates the actual statement.
        Map<String, Object> p = new HashMap<>();
        p.put("sql", "SELECT 1");
        assertEquals(
            "write",
            Capabilities.deriveRequiredCapability("execute", p)
        );
        assertEquals(
            "write",
            Capabilities.deriveRequiredCapability("deploy", p)
        );
    }

    @Test
    void emptyOrBlankShellCommandFallsThroughToWrite() {
        Map<String, Object> p = new HashMap<>();
        p.put("shell_command", "   ");
        assertEquals(
            "write",
            Capabilities.deriveRequiredCapability("execute", p)
        );
    }

    @Test
    void unknownActionReturnsNull() {
        // Unknown action is a parse error at the dispatch layer, NOT a
        // capability denial — the existing 'Unknown action' branch
        // must still fire so we don't conflate the two failure modes.
        assertNull(Capabilities.deriveRequiredCapability("teleport", null));
        assertNull(Capabilities.deriveRequiredCapability("", null));
        assertNull(Capabilities.deriveRequiredCapability(null, null));
    }

    @Test
    void deployAndExecuteSharePath() {
        Map<String, Object> p = new HashMap<>();
        p.put("operation", "read");
        assertEquals(
            Capabilities.deriveRequiredCapability("execute", p),
            Capabilities.deriveRequiredCapability("deploy", p)
        );
    }
}
