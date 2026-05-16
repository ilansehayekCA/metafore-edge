package com.metafore.edge.config;

/**
 * Phase 14.9 — Edge process topology classification.
 *
 * Advertised on the registration MQTT payload's {@code runtime} field
 * so the platform can disambiguate mount-time host strings (e.g.
 * {@code host.docker.internal} vs {@code localhost}). Wire values
 * are kebab-case strings to match the canonical JSON-schema enum
 * (see {@code contracts/mqtt/registration.schema.json}).
 *
 * <p>The four states are deliberately small and stable. New cases
 * (k8s, podman, rootless-docker) should be classified via heuristics
 * onto these wire values rather than introducing new enum entries —
 * the platform-side rule table in
 * {@code metafore_core/services/host_compat.py} is keyed on this
 * vocabulary, and growing the vocabulary churns that rule table.
 *
 * <p>If the auto-probe cannot determine the runtime, {@link #UNKNOWN}
 * is a first-class wire value — the platform treats absence and
 * {@code unknown} identically and the assistant degrades gracefully
 * to "I can't tell which topology this AC is running in".
 */
public enum EdgeRuntime {
    /** Edge process inside a Docker container on the default bridge. */
    DOCKER("docker"),
    /** Edge process running directly on the host JVM (no container). */
    NATIVE("native"),
    /** Edge process inside Docker with {@code --network=host}. */
    DOCKER_HOST_NETWORK("docker-host-network"),
    /** Auto-probe inconclusive; operator can override via EDGE_RUNTIME. */
    UNKNOWN("unknown");

    private final String wire;

    EdgeRuntime(String wire) {
        this.wire = wire;
    }

    /** Wire (kebab-case) string used on the registration payload. */
    public String wire() {
        return wire;
    }

    /**
     * Parse a wire string into an enum value. Case-insensitive against
     * the wire form (so {@code Docker}, {@code DOCKER}, {@code docker}
     * all resolve identically). Underscore variants are also accepted
     * so an operator typo like {@code DOCKER_HOST_NETWORK} resolves.
     * Unrecognised input returns {@link #UNKNOWN}.
     */
    public static EdgeRuntime fromWire(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        String normalized = raw.trim().toLowerCase().replace('_', '-');
        for (EdgeRuntime r : values()) {
            if (r.wire.equals(normalized)) {
                return r;
            }
        }
        return UNKNOWN;
    }
}
