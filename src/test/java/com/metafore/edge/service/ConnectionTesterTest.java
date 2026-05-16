package com.metafore.edge.service;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 14.12 / MTBC.T1 — unit tests for the typed-reason classifier.
 *
 * The full ProbeResult round-trip against a real PG is covered by
 * integration tests; these unit tests pin the SQLState-to-reason
 * mapping and the cause-chain unwrapping so the classifier stays
 * stable as PG driver versions change.
 */
class ConnectionTesterTest {

    @Test
    void classifyAuthFailedByState28P01() {
        SQLException ex = new SQLException(
            "FATAL: password authentication failed for user \"foo\"",
            "28P01");
        ConnectionTester.ProbeResult r = ConnectionTester.classify(ex, 12L);
        assertFalse(r.ok());
        assertEquals("auth_failed", r.reason());
        assertEquals(12L, r.latencyMs());
        assertTrue(r.message().contains("password authentication failed"));
    }

    @Test
    void classifyAuthFailedByState28000() {
        SQLException ex = new SQLException("auth bad", "28000");
        ConnectionTester.ProbeResult r = ConnectionTester.classify(ex, 0L);
        assertEquals("auth_failed", r.reason());
    }

    @Test
    void classifyDatabaseNotFoundByState3D000() {
        SQLException ex = new SQLException(
            "FATAL: database \"missing\" does not exist", "3D000");
        ConnectionTester.ProbeResult r = ConnectionTester.classify(ex, 0L);
        assertEquals("database_not_found", r.reason());
    }

    @Test
    void classifyHostUnresolvedViaCauseChain() {
        UnknownHostException uhe =
            new UnknownHostException("host.docker.internal");
        SQLException ex = new SQLException(
            "Could not open a connection to the server", "08001", uhe);
        ConnectionTester.ProbeResult r = ConnectionTester.classify(ex, 23L);
        assertEquals("host_unresolved", r.reason());
        assertTrue(r.message().contains("host.docker.internal"));
    }

    @Test
    void classifyConnectionRefusedViaCauseChain() {
        ConnectException ce = new ConnectException("Connection refused");
        SQLException ex = new SQLException("connect failure", "08001", ce);
        ConnectionTester.ProbeResult r = ConnectionTester.classify(ex, 1L);
        assertEquals("connection_refused", r.reason());
    }

    @Test
    void classifyHostUnresolvedViaMessageFallback() {
        // No typed cause, but the driver's message contains the
        // canonical phrase. Fallback path catches it.
        SQLException ex = new SQLException(
            "The connection attempt failed: unknown host xyz", "08001");
        ConnectionTester.ProbeResult r = ConnectionTester.classify(ex, 0L);
        assertEquals("host_unresolved", r.reason());
    }

    @Test
    void classifyAuthFailedViaMessageFallback() {
        SQLException ex = new SQLException(
            "FATAL: password authentication failed for user", (String) null);
        ConnectionTester.ProbeResult r = ConnectionTester.classify(ex, 0L);
        assertEquals("auth_failed", r.reason());
    }

    @Test
    void classifyUnknownFallback() {
        SQLException ex = new SQLException("totally bizarre", "00000");
        ConnectionTester.ProbeResult r = ConnectionTester.classify(ex, 5L);
        assertEquals("unknown", r.reason());
        assertEquals("totally bizarre", r.message());
    }

    @Test
    void probeBlankHostReturnsUnknown() {
        ConnectionTester.ProbeResult r =
            ConnectionTester.probe("", "5432", "db", "u", "p");
        assertFalse(r.ok());
        assertEquals("unknown", r.reason());
        assertTrue(r.message().contains("host"));
    }

    @Test
    void probeNonNumericPortReturnsUnknown() {
        ConnectionTester.ProbeResult r =
            ConnectionTester.probe("localhost", "notaport", "db", "u", "p");
        assertFalse(r.ok());
        assertEquals("unknown", r.reason());
        assertTrue(r.message().contains("notaport"));
    }

    @Test
    void probeUnresolvableHostReturnsHostUnresolved() {
        // Using a TLD that's reserved and guaranteed non-resolvable
        // per RFC 6761. No network round-trip required.
        ConnectionTester.ProbeResult r = ConnectionTester.probe(
            "this-host-does-not-exist.invalid",
            "5432", "any", "u", "p");
        assertFalse(r.ok());
        assertEquals("host_unresolved", r.reason());
    }
}
