package com.metafore.edge.route;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RouteExecutorRouteTest {

    @Test
    void extractQueryFromYaml() {
        String yaml = "name: test-route\nquery: SELECT * FROM users\nparams: none";
        assertEquals("SELECT * FROM users", RouteExecutorRoute.extractQuery(yaml));
    }

    @Test
    void extractQueryMultiLine() {
        String yaml = "query: >\n  SELECT *\n  FROM users\n- next_section";
        assertEquals("SELECT * FROM users", RouteExecutorRoute.extractQuery(yaml));
    }

    @Test
    void extractQueryNull() {
        assertNull(RouteExecutorRoute.extractQuery(null));
        assertNull(RouteExecutorRoute.extractQuery("no query here"));
    }

    @Test
    void extractShellCommand() {
        String yaml = "exec: ps aux\nnext: value";
        assertEquals("ps aux", RouteExecutorRoute.extractShellCommand(yaml));
    }

    @Test
    void extractShellCommandNull() {
        assertNull(RouteExecutorRoute.extractShellCommand(null));
        assertNull(RouteExecutorRoute.extractShellCommand("no exec here"));
    }

    // ── Slice 33.1.0 — payload classification at dispatch boundary ──

    @Test
    void classifyPayloadParametric() {
        java.util.Map<String, Object> p = new java.util.HashMap<>();
        p.put("operation", "create");
        p.put("table_name", "gtm_accounts");
        assertEquals(
            RouteExecutorRoute.PayloadKind.PARAMETRIC,
            RouteExecutorRoute.classifyPayload(p, null)
        );
    }

    @Test
    void classifyPayloadLegacy() {
        java.util.Map<String, Object> p = new java.util.HashMap<>();
        // No 'operation' key — legacy SQL path.
        assertEquals(
            RouteExecutorRoute.PayloadKind.LEGACY,
            RouteExecutorRoute.classifyPayload(p, "SELECT 1")
        );
    }

    @Test
    void classifyPayloadAmbiguousBothPresent() {
        // Defense-in-depth: a payload carrying BOTH new parametric
        // 'operation' AND legacy 'sql' is a routing-layer bug.
        // Edge fails-closed rather than picking one and proceeding.
        java.util.Map<String, Object> p = new java.util.HashMap<>();
        p.put("operation", "create");
        assertEquals(
            RouteExecutorRoute.PayloadKind.AMBIGUOUS,
            RouteExecutorRoute.classifyPayload(p, "SELECT 1")
        );
    }

    @Test
    void classifyPayloadMissingNeitherPresent() {
        // Empty params + no SQL anywhere → MISSING. Caller built a
        // malformed payload; edge surfaces a clear error.
        java.util.Map<String, Object> p = new java.util.HashMap<>();
        assertEquals(
            RouteExecutorRoute.PayloadKind.MISSING,
            RouteExecutorRoute.classifyPayload(p, null)
        );
        assertEquals(
            RouteExecutorRoute.PayloadKind.MISSING,
            RouteExecutorRoute.classifyPayload(p, "")
        );
        assertEquals(
            RouteExecutorRoute.PayloadKind.MISSING,
            RouteExecutorRoute.classifyPayload(null, null)
        );
    }

    @Test
    void classifyPayloadBlankOperationStringTreatedAsMissing() {
        // Caller passing an empty-string 'operation' shouldn't be
        // accepted as parametric — that's a malformed payload, not
        // an explicit choice. Falls through to LEGACY/MISSING based
        // on whether sql is present.
        java.util.Map<String, Object> p = new java.util.HashMap<>();
        p.put("operation", "   ");
        assertEquals(
            RouteExecutorRoute.PayloadKind.MISSING,
            RouteExecutorRoute.classifyPayload(p, null)
        );
        assertEquals(
            RouteExecutorRoute.PayloadKind.LEGACY,
            RouteExecutorRoute.classifyPayload(p, "SELECT 1")
        );
    }
}
