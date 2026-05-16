package com.metafore.edge.service;

import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 14.12 / MTBC.T1 — synchronous "connect and ping" probe used by
 * the mount-flow test_connection round-trip.
 *
 * <p>Why it's a distinct verb (not a {@code SELECT 1} on the existing
 * execute path):
 * <ul>
 *   <li>The {@link DataSourceRegistry} register-on-execute path
 *       <em>persists</em> the {@code DataSource} under the routeId.
 *       A connection test must be ephemeral — leaving a stale registry
 *       entry behind would let the next read silently reuse the
 *       wrong-host credentials. {@code connect_and_ping} opens a
 *       transient {@link PGSimpleDataSource}, runs the probe, and lets
 *       the JVM GC it.</li>
 *   <li>{@link SqlExecutor#isAllowed} whitelists generic SQL. A test
 *       probe is a fixed shape (version + current_database) — routing
 *       it through the whitelist surface is over-broad and muddies
 *       audit telemetry.</li>
 *   <li>Per-verb dispatch keeps telemetry clean: every
 *       {@code connect_and_ping} log line is unambiguously a mount-flow
 *       probe, not a routine read.</li>
 * </ul>
 *
 * <p>Failure modes produce typed reasons the assistant can route on
 * without parsing free-text — {@link ProbeResult#reason()} is one of
 * {@code host_unresolved}, {@code connection_refused}, {@code auth_failed},
 * {@code database_not_found}, or {@code unknown}.
 */
public final class ConnectionTester {

    private static final Logger LOG =
        LoggerFactory.getLogger(ConnectionTester.class);

    /**
     * Login timeout (seconds) applied to the transient datasource.
     * PG's default is "wait forever"; a mount-flow test must fail fast
     * so the assistant can surface the failure within the user's
     * conversation turn.
     */
    static final int LOGIN_TIMEOUT_SECONDS = 8;

    /**
     * Statement timeout (seconds) applied to the probe query. {@code
     * SELECT version(), current_database()} is microseconds on any
     * healthy PG; bounded so a misconfigured server (e.g. paused PG)
     * can't hang the route thread.
     */
    static final int STATEMENT_TIMEOUT_SECONDS = 5;

    private ConnectionTester() {}

    /**
     * Typed envelope returned by {@link #probe}. The four fields the
     * mount-flow needs: ok/not, latency (timing-side budget for SLA
     * tracking), the probe metadata when ok, the typed reason +
     * message when not.
     */
    public static final class ProbeResult {
        private final boolean ok;
        private final long latencyMs;
        private final List<Map<String, Object>> data;
        private final String reason;
        private final String message;

        private ProbeResult(boolean ok, long latencyMs,
                            List<Map<String, Object>> data,
                            String reason, String message) {
            this.ok = ok;
            this.latencyMs = latencyMs;
            this.data = data;
            this.reason = reason;
            this.message = message;
        }

        public boolean ok() { return ok; }
        public long latencyMs() { return latencyMs; }
        public List<Map<String, Object>> data() { return data; }
        public String reason() { return reason; }
        public String message() { return message; }
    }

    /**
     * Run the connect-and-ping probe with the given parameters. Never
     * throws — all failure modes route to a {@link ProbeResult} carrying
     * the typed reason.
     */
    public static ProbeResult probe(String host, String port,
                                    String database, String user,
                                    String password) {
        long start = System.currentTimeMillis();
        if (host == null || host.isBlank()) {
            return new ProbeResult(false, 0L, null, "unknown",
                "host is required");
        }
        int portNum;
        try {
            portNum = Integer.parseInt(port == null ? "5432" : port);
        } catch (NumberFormatException nfe) {
            return new ProbeResult(false, 0L, null, "unknown",
                "port is not a number: " + port);
        }

        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setServerNames(new String[]{host});
        ds.setPortNumbers(new int[]{portNum});
        if (database != null && !database.isEmpty()) {
            ds.setDatabaseName(database);
        }
        if (user != null && !user.isEmpty()) {
            ds.setUser(user);
        }
        if (password != null) {
            ds.setPassword(password);
        }
        // setLoginTimeout on PGSimpleDataSource is the JDBC-level
        // login timeout in seconds (not via SQLException — different
        // signature on PGSimpleDataSource vs the generic
        // CommonDataSource.setLoginTimeout(int) which is declared
        // throws-SQLException on some JDKs). Call the int overload
        // directly — never throws.
        ds.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);

        try (Connection conn = ds.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.setQueryTimeout(STATEMENT_TIMEOUT_SECONDS);
                try (ResultSet rs =
                         st.executeQuery("SELECT version(), current_database()")) {
                    List<Map<String, Object>> data = new ArrayList<>();
                    if (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("version", rs.getString(1));
                        row.put("current_database", rs.getString(2));
                        data.add(row);
                    }
                    long elapsed = System.currentTimeMillis() - start;
                    LOG.info("connect_and_ping ok host={} port={} db={} latency_ms={}",
                        host, portNum, database, elapsed);
                    return new ProbeResult(true, elapsed, data, null, null);
                }
            }
        } catch (SQLException sqlEx) {
            long elapsed = System.currentTimeMillis() - start;
            ProbeResult typed = classify(sqlEx, elapsed);
            LOG.info("connect_and_ping failed host={} port={} db={} reason={} latency_ms={}",
                host, portNum, database, typed.reason(), elapsed);
            return typed;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return new ProbeResult(false, elapsed, null, "unknown",
                e.getClass().getSimpleName() + ": " + safeMsg(e));
        }
    }

    /**
     * Map a {@link SQLException} (and its underlying cause) to a typed
     * reason string the assistant can route on without parsing
     * free-text. Order matters — auth-state codes are most specific,
     * then DNS, then connect-refused, then generic.
     */
    static ProbeResult classify(SQLException sqlEx, long latencyMs) {
        String sqlState = sqlEx.getSQLState();
        String rawMsg = safeMsg(sqlEx);

        // PG-specific SQLSTATE codes are the strongest signal.
        if ("28P01".equals(sqlState) || "28000".equals(sqlState)) {
            // 28P01 = invalid_password; 28000 = invalid_authorization_specification
            return new ProbeResult(false, latencyMs, null, "auth_failed", rawMsg);
        }
        if ("3D000".equals(sqlState)) {
            // 3D000 = invalid_catalog_name (database does not exist)
            return new ProbeResult(false, latencyMs, null,
                "database_not_found", rawMsg);
        }

        // Walk the cause chain for DNS / network errors. PG wraps these
        // in a SQLException whose getCause() is the IOException.
        Throwable cause = sqlEx.getCause();
        while (cause != null) {
            if (cause instanceof UnknownHostException) {
                return new ProbeResult(false, latencyMs, null,
                    "host_unresolved", "UnknownHostException: " + safeMsg(cause));
            }
            String causeClass = cause.getClass().getSimpleName();
            String causeMsg = safeMsg(cause);
            if ("ConnectException".equals(causeClass)) {
                return new ProbeResult(false, latencyMs, null,
                    "connection_refused", "ConnectException: " + causeMsg);
            }
            if ("NoRouteToHostException".equals(causeClass)
                || "SocketTimeoutException".equals(causeClass)) {
                return new ProbeResult(false, latencyMs, null,
                    "connection_refused",
                    causeClass + ": " + causeMsg);
            }
            cause = cause.getCause();
        }

        // Fall back to message-substring sniffing for drivers that
        // don't surface a typed cause. PG JDBC's "Connection to
        // host:port refused" path is the canonical one we care about.
        String lower = rawMsg.toLowerCase();
        if (lower.contains("unknown host") || lower.contains("not resolve")) {
            return new ProbeResult(false, latencyMs, null,
                "host_unresolved", rawMsg);
        }
        if (lower.contains("connection refused")
            || lower.contains("connect timed out")) {
            return new ProbeResult(false, latencyMs, null,
                "connection_refused", rawMsg);
        }
        if (lower.contains("password authentication failed")
            || lower.contains("authentication failed")) {
            return new ProbeResult(false, latencyMs, null,
                "auth_failed", rawMsg);
        }
        if (lower.contains("does not exist")
            && lower.contains("database")) {
            return new ProbeResult(false, latencyMs, null,
                "database_not_found", rawMsg);
        }

        return new ProbeResult(false, latencyMs, null, "unknown", rawMsg);
    }

    private static String safeMsg(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }
}
