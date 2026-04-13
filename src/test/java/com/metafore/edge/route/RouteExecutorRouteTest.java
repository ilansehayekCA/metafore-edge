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
}
