package com.metafore.edge.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class EdgeConfig {

    private final String controllerId;
    private final String tenantId;
    private final List<String> tenants;
    private final String brokerUrl;
    private final String edgeVersion;
    private final String dbHost;
    private final String dbPort;
    private final String dbName;
    private final String dbUser;
    private final String dbPass;
    private final String logSource;
    private final long heartbeatIntervalMs;
    private final long discoveryDelayMs;
    private final int defaultRowFetchSize;
    /** Phase 14.9 / ETA.T1 — auto-detected edge process topology.
     *  Wire value (kebab-case) is what ships on the registration
     *  payload's {@code runtime} field; {@link EdgeRuntime#UNKNOWN}
     *  is a first-class state, not an error. */
    private final EdgeRuntime runtime;
    /** Phase 14.9 / ETA.T1 — frozen diagnostic context backing the
     *  {@link #runtime} classification. Whitelisted to 5 keys
     *  (os_name, java_version, hostname, docker_env_file_present,
     *  cgroup_signature) per brief 14.9 §Risks. */
    private final Map<String, Object> runtimeHints;

    /**
     * Phase 13 / REK.T6 — default JDBC row fetch size for PG-backed
     * DataSources. Acts as a safety net against unbounded SELECTs:
     * Postgres streams rows in {@code defaultRowFetchSize}-row chunks
     * so the JVM materializes one chunk at a time. Brief 10's keyset
     * pagination + LIMIT N+1 remains the primary mechanism; this is
     * the catastrophe brake.
     */
    public static final int DEFAULT_ROW_FETCH_SIZE = 500;

    /**
     * Phase 14.18 — default edge wire-protocol version.
     *
     * <p>Bumped from {@code 1.1.0} → {@code 1.2.0} when the registration
     * payload gained the optional {@code tenants} array (multi-tenant
     * edge binding). Older cores ignore the new field; older edges that
     * still report {@code 1.1.0} are treated as single-tenant by core's
     * {@code _handle_registration} (legacy {@code tenant_id} branch).
     *
     * <p>Version history:
     * <ul>
     *   <li>{@code 1.0.0} — Phase 13 baseline.
     *   <li>{@code 1.1.0} — Phase 14.9: added {@code runtime} +
     *       {@code runtime_hints}.
     *   <li>{@code 1.2.0} — Phase 14.18: added {@code tenants}
     *       (multi-tenant binding).
     * </ul>
     */
    public static final String DEFAULT_EDGE_VERSION = "1.2.0";

    private EdgeConfig(Map<String, String> env) {
        this(env, RuntimeProbe.Probes.system());
    }

    private EdgeConfig(Map<String, String> env, RuntimeProbe.Probes probes) {
        this.controllerId       = env.getOrDefault("CONTROLLER_ID", "edge-default");
        this.tenantId           = env.getOrDefault("TENANT_ID", "default-tenant");
        // Phase 14.18 — TENANT_IDS (comma-separated multi-tenant list)
        // wins when present; otherwise fall back to the singleton
        // TENANT_ID as a single-element list (legacy edges keep working
        // without env churn). See parseTenantsEnv() for trim / dedupe /
        // drop-empty rules. Stored as an unmodifiable list so downstream
        // RegistrationRoute / RouteExecutorRoute treat it as immutable.
        this.tenants            = Collections.unmodifiableList(
            parseTenantsEnv(env.getOrDefault("TENANT_IDS", ""), this.tenantId));
        this.brokerUrl          = env.getOrDefault("BROKER_URL", "tcp://mqtt-broker:1883");
        this.edgeVersion        = env.getOrDefault("EDGE_VERSION", DEFAULT_EDGE_VERSION);
        this.dbHost             = env.getOrDefault("DB_HOST", "localhost");
        this.dbPort             = env.getOrDefault("DB_PORT", "5432");
        this.dbName             = env.getOrDefault("DB_NAME", "");
        this.dbUser             = env.getOrDefault("DB_USER", "root");
        this.dbPass             = env.getOrDefault("DB_PASS", "");
        this.logSource          = env.getOrDefault("LOG_SOURCE", "/var/log/monitored/app.log");
        this.heartbeatIntervalMs = Long.parseLong(env.getOrDefault("HEARTBEAT_INTERVAL_MS", "30000"));
        this.discoveryDelayMs   = Long.parseLong(env.getOrDefault("DISCOVERY_DELAY_MS", "5000"));
        this.defaultRowFetchSize = parseDefaultRowFetchSize(
            env.getOrDefault("DEFAULT_ROW_FETCH_SIZE",
                Integer.toString(DEFAULT_ROW_FETCH_SIZE))
        );
        // Phase 14.9 — detect once at construction. Probe failures
        // never throw — the detect() contract returns UNKNOWN on any
        // ambiguity. Hints map carries the diagnostic context.
        this.runtime      = RuntimeProbe.detect(probes);
        this.runtimeHints = Collections.unmodifiableMap(RuntimeProbe.hints(probes));
    }

    /**
     * Phase 14.18 — Parse {@code TENANT_IDS} (comma-separated tenant
     * slug list) into a normalized list.
     *
     * <p>Rules:
     * <ul>
     *   <li>Split on {@code ,}.
     *   <li>Trim whitespace per element.
     *   <li>Drop empty / blank elements.
     *   <li>Preserve insertion order; dedupe (case-preserving).
     *   <li>If the resulting list is empty AND {@code legacyTenantId}
     *       is non-blank, return {@code [legacyTenantId]} (back-compat
     *       with single-tenant TENANT_ID deployments).
     *   <li>If both are empty, return an empty list — callers
     *       (RegistrationRoute, RouteExecutorRoute) must tolerate this
     *       defensively (no subscriptions, no payload `tenants` field).
     * </ul>
     */
    static List<String> parseTenantsEnv(String raw, String legacyTenantId) {
        List<String> result = new ArrayList<>();
        if (raw != null && !raw.isBlank()) {
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (String part : raw.split(",")) {
                String trimmed = part == null ? "" : part.trim();
                if (trimmed.isEmpty()) continue;
                if (seen.add(trimmed)) {
                    result.add(trimmed);
                }
            }
        }
        if (result.isEmpty() && legacyTenantId != null
                && !legacyTenantId.isBlank()) {
            result.add(legacyTenantId.trim());
        }
        return result;
    }

    /**
     * Phase 13 / REK.T6 — Parse the configured fetch size. Non-positive
     * or unparseable values fall back to {@link #DEFAULT_ROW_FETCH_SIZE}
     * so a typo in deployment env does not silently disable the safety
     * net by passing fetchSize=0 (which PG JDBC interprets as "no
     * cursor streaming — materialize everything").
     */
    private static int parseDefaultRowFetchSize(String raw) {
        try {
            int value = Integer.parseInt(raw);
            return value > 0 ? value : DEFAULT_ROW_FETCH_SIZE;
        } catch (NumberFormatException e) {
            return DEFAULT_ROW_FETCH_SIZE;
        }
    }

    public static EdgeConfig fromSystem() {
        return new EdgeConfig(System.getenv());
    }

    public static EdgeConfig from(Map<String, String> env) {
        return new EdgeConfig(env);
    }

    /** Phase 14.9 / ETA.T1 — Test-only entry point allowing injection
     *  of a deterministic {@link RuntimeProbe.Probes} so unit tests
     *  can exercise the layered probe without spelunking through the
     *  real filesystem. */
    public static EdgeConfig from(Map<String, String> env, RuntimeProbe.Probes probes) {
        return new EdgeConfig(env, probes);
    }

    public String controllerId()       { return controllerId; }
    public String tenantId()           { return tenantId; }

    /**
     * Phase 14.18 — list of tenant slugs this physical edge serves.
     *
     * <p>Parsed from {@code TENANT_IDS} (comma-separated) at startup;
     * falls through to {@code [TENANT_ID]} when {@code TENANT_IDS} is
     * unset (back-compat with pre-1.2.0 single-tenant deployments).
     * The list is immutable — never returns null, may return an empty
     * list when both env vars are blank (callers tolerate empty for
     * test / sandbox boot).
     */
    public List<String> tenants()      { return tenants; }
    public String brokerUrl()          { return brokerUrl; }
    public String edgeVersion()        { return edgeVersion; }
    public String dbHost()             { return dbHost; }
    public String dbPort()             { return dbPort; }
    public String dbName()             { return dbName; }
    public String dbUser()             { return dbUser; }
    public String dbPass()             { return dbPass; }
    public String logSource()          { return logSource; }
    public long heartbeatIntervalMs()  { return heartbeatIntervalMs; }
    public long discoveryDelayMs()     { return discoveryDelayMs; }
    public int defaultRowFetchSize()   { return defaultRowFetchSize; }

    /** Phase 14.9 / ETA.T1 — wire string form of the runtime
     *  classification, ready to drop on the registration payload's
     *  {@code runtime} field. Never null. */
    public String runtime() {
        return runtime.wire();
    }

    /** Phase 14.9 / ETA.T1 — frozen diagnostic context map shipped on
     *  the registration payload's {@code runtime_hints} field. Always
     *  carries the 5 keys defined in the brief; values may be
     *  {@code "unavailable"} string placeholders where the probe
     *  couldn't read. Never null. */
    public Map<String, Object> runtimeHints() {
        return runtimeHints;
    }
}
