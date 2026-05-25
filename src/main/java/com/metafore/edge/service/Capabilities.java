package com.metafore.edge.service;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CCA.T2 / compass ba53231a — canonical AC capability set + the
 * action→capability mapping used both to advertise at registration
 * and to enforce at dispatch.
 *
 * <p>The canonical names mirror metafore-core's
 * {@code KNOWN_AC_CAPABILITIES} (see {@code metafore_core/mqtt.py}):
 * {@code read} / {@code write} / {@code ddl} / {@code introspect} /
 * {@code test_connection} / {@code healthcheck} / {@code shell} /
 * {@code sync}.
 *
 * <p>This image declares the subset it actually serves. The dispatcher
 * enforces against the same constant — so an edge image that ships a
 * narrower set can refuse commands it never claimed to support, and
 * the platform-side preflight (CCA.T4) sees the truth at
 * registration time.
 *
 * <p>Today's edge image serves every canonical capability except
 * {@code sync} (no continuous-reconciliation path on edge yet —
 * Equilibrium method E sync is platform-side). To narrow the
 * advertisement for a restricted-profile build, edit
 * {@link #DECLARED} and ensure every reachable action in
 * {@link #deriveRequiredCapability} that maps to a removed capability
 * gets rejected at dispatch.
 */
public final class Capabilities {

    private Capabilities() {}

    /** Canonical capability names this edge image advertises + enforces. */
    public static final Set<String> DECLARED = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList(
            "shell",
            "test_connection",
            "healthcheck",
            "introspect",
            "read",
            "write",
            "ddl"
        ))
    );

    /** Immutable list view suitable for the registration payload. */
    public static List<String> declaredList() {
        return List.copyOf(DECLARED);
    }

    /**
     * Map a route command shape to the single canonical capability it
     * requires. Returns {@code null} when the action is unknown — the
     * dispatcher treats unknown actions as a parse error (not a
     * capability denial).
     *
     * <p>Mapping rules:
     * <ul>
     *   <li>{@code connect_and_ping} → {@code test_connection}</li>
     *   <li>{@code remove} → {@code write} (mutates the on-edge
     *       DataSourceRegistry)</li>
     *   <li>{@code deploy} / {@code execute}:
     *     <ul>
     *       <li>shell branch (params has {@code shell_command} or
     *           {@code route_yaml} contains {@code exec:}) →
     *           {@code shell}</li>
     *       <li>parametric {@code operation in {read, select}} →
     *           {@code read}</li>
     *       <li>parametric {@code operation in {create, update, delete}}
     *           → {@code write}</li>
     *       <li>otherwise (legacy SQL path) → {@code write} (coarse
     *           upper-bound; the SQL whitelist in {@code SqlExecutor}
     *           still gates the exact statement, but capability-level
     *           enforcement treats every legacy execute as a potential
     *           write so a read-only edge cannot accept legacy SQL).
     *           </li>
     *     </ul>
     *   </li>
     * </ul>
     */
    public static String deriveRequiredCapability(
        String action, Map<String, Object> params
    ) {
        if (action == null) {
            return null;
        }
        switch (action) {
            case "connect_and_ping":
                return "test_connection";
            case "remove":
                return "write";
            case "deploy":
            case "execute":
                return deriveForExecute(params);
            default:
                return null;
        }
    }

    private static String deriveForExecute(Map<String, Object> params) {
        if (params != null) {
            Object shellCmd = params.get("shell_command");
            if (shellCmd instanceof String && !((String) shellCmd).isBlank()) {
                return "shell";
            }
            Object yaml = params.get("route_yaml");
            if (yaml instanceof String && ((String) yaml).contains("exec:")) {
                return "shell";
            }
            Object op = params.get("operation");
            if (op instanceof String) {
                String opStr = ((String) op).trim().toLowerCase();
                if ("read".equals(opStr) || "select".equals(opStr)) {
                    return "read";
                }
                if ("create".equals(opStr) || "update".equals(opStr)
                    || "delete".equals(opStr)) {
                    return "write";
                }
            }
        }
        return "write";
    }
}
