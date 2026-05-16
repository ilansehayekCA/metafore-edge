package com.metafore.edge.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Phase 14.9 / ETA.T1 — Detects the {@link EdgeRuntime} of the
 * surrounding process at startup.
 *
 * <p>Layered probe (first match wins, fall through to UNKNOWN):
 * <ol>
 *   <li>Explicit {@code EDGE_RUNTIME} env var override (case-
 *       insensitive map onto the enum; unparseable literal logs WARN
 *       and yields {@code UNKNOWN}).</li>
 *   <li>{@code /.dockerenv} file existence — canonical Docker-on-
 *       Linux marker. Works inside Docker-for-Windows containers too.</li>
 *   <li>{@code /proc/1/cgroup} parse — Linux-only marker; harmless
 *       absence on Windows (returns "unavailable" hint).</li>
 *   <li>{@code HOSTNAME} env var = 12-hex-char Docker default — only
 *       consulted when {@code /proc} is unreadable.</li>
 *   <li>Host-network upgrade — when DOCKER is detected, {@code /proc/
 *       net/dev} listing more than two non-loopback interfaces
 *       upgrades to {@link EdgeRuntime#DOCKER_HOST_NETWORK}.</li>
 *   <li>JVM property fallback — {@code os.name} containing Windows /
 *       Linux / Mac OS X without container markers returns
 *       {@link EdgeRuntime#NATIVE}.</li>
 *   <li>Otherwise {@link EdgeRuntime#UNKNOWN} (with a single WARN log
 *       so operators see the ambiguity).</li>
 * </ol>
 *
 * <p>The probe is invoked once at {@link EdgeConfig#fromSystem()} and
 * the result is cached on the config instance. A topology change
 * requires an AC restart (which re-registers and reports the new
 * runtime) — see brief 14.9 §Out-of-scope.
 *
 * <p>All filesystem and env reads are routed through the injectable
 * {@link Probes} record so tests can drive the layering deterministically
 * without spelunking through the real filesystem. The package-private
 * {@link #detect(Probes)} overload is the testable seam;
 * {@link #detect()} is the production entry point.
 */
public final class RuntimeProbe {

    private static final Logger LOG = LoggerFactory.getLogger(RuntimeProbe.class);

    /** Docker container-id default-hostname pattern (12 hex chars). */
    private static final Pattern DOCKER_HOSTNAME =
        Pattern.compile("^[0-9a-f]{12}$");

    /** cgroup markers — line contains any of these → DOCKER. */
    private static final List<String> CGROUP_DOCKER_MARKERS =
        List.of("docker", "containerd", "kubepods");

    private RuntimeProbe() {}

    /**
     * Inject point for filesystem + env access. Lets tests override
     * each signal independently without monkey-patching System.
     */
    public record Probes(
        java.util.function.Function<String, String> env,
        java.util.function.Function<String, String> sysProp,
        java.util.function.Function<Path, Boolean> fileExists,
        java.util.function.Function<Path, String> fileRead
    ) {
        /** Default Probes — reads real env, real System properties,
         *  real filesystem. */
        public static Probes system() {
            return new Probes(
                System::getenv,
                System::getProperty,
                p -> {
                    try {
                        return Files.exists(p);
                    } catch (Exception e) {
                        return false;
                    }
                },
                p -> {
                    try {
                        return Files.readString(p);
                    } catch (IOException | RuntimeException e) {
                        return null;
                    }
                }
            );
        }
    }

    /** Production entry point — runs the probe against the real
     *  process. Called once from {@link EdgeConfig#fromSystem()}. */
    public static EdgeRuntime detect() {
        return detect(Probes.system());
    }

    /** Testable entry point — runs the probe against an injected
     *  Probes. Package-private so tests can drive it deterministically. */
    static EdgeRuntime detect(Probes probes) {
        // (1) Explicit override
        String override = probes.env().apply("EDGE_RUNTIME");
        if (override != null && !override.isBlank()) {
            EdgeRuntime parsed = EdgeRuntime.fromWire(override);
            if (parsed == EdgeRuntime.UNKNOWN
                    && !override.trim().equalsIgnoreCase("unknown")) {
                LOG.warn(
                    "EDGE_RUNTIME={} not recognized; falling back to UNKNOWN. "
                    + "Valid values: docker, native, docker-host-network, unknown.",
                    override
                );
            }
            return parsed;
        }

        // (2) /.dockerenv
        boolean hasDockerEnvFile = probes.fileExists().apply(Path.of("/.dockerenv"));
        if (hasDockerEnvFile) {
            return upgradeForHostNetwork(probes, EdgeRuntime.DOCKER);
        }

        // (3) /proc/1/cgroup
        String cgroupContent = probes.fileRead().apply(Path.of("/proc/1/cgroup"));
        if (cgroupContent != null) {
            String lower = cgroupContent.toLowerCase();
            for (String marker : CGROUP_DOCKER_MARKERS) {
                if (lower.contains(marker)) {
                    return upgradeForHostNetwork(probes, EdgeRuntime.DOCKER);
                }
            }
        }

        // (4) HOSTNAME heuristic — only when /proc unreadable
        if (cgroupContent == null) {
            String hostname = probes.env().apply("HOSTNAME");
            if (hostname != null && DOCKER_HOSTNAME.matcher(hostname).matches()) {
                // Don't try host-network upgrade — we have no /proc.
                return EdgeRuntime.DOCKER;
            }
        }

        // (5) JVM property hint — native fallback
        String osName = probes.sysProp().apply("os.name");
        if (osName != null) {
            String low = osName.toLowerCase();
            if (low.contains("windows") || low.contains("mac os")
                    || low.contains("darwin") || low.contains("linux")) {
                return EdgeRuntime.NATIVE;
            }
        }

        // (6) Fallthrough
        LOG.warn(
            "RuntimeProbe could not classify the edge runtime "
            + "(no EDGE_RUNTIME, no /.dockerenv, no cgroup signal, "
            + "no recognized os.name). Reporting UNKNOWN — set "
            + "EDGE_RUNTIME=<docker|native|docker-host-network> on the "
            + "edge process to silence."
        );
        return EdgeRuntime.UNKNOWN;
    }

    /** When DOCKER was detected, try to upgrade to
     *  DOCKER_HOST_NETWORK based on the /proc/net/dev interface list. */
    private static EdgeRuntime upgradeForHostNetwork(Probes probes, EdgeRuntime base) {
        if (base != EdgeRuntime.DOCKER) {
            return base;
        }
        String netDev = probes.fileRead().apply(Path.of("/proc/net/dev"));
        if (netDev == null) {
            return base;
        }
        int nonLoopback = 0;
        for (String line : netDev.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("Inter-")
                    || trimmed.startsWith("face")) {
                continue;
            }
            // Format: "<ifname>:<stats...>". Pull the interface label.
            int colonIdx = trimmed.indexOf(':');
            if (colonIdx < 0) {
                continue;
            }
            String ifname = trimmed.substring(0, colonIdx).trim();
            if (ifname.isEmpty() || ifname.equals("lo")) {
                continue;
            }
            nonLoopback++;
        }
        // Heuristic: a default-bridge container has one non-lo interface
        // (eth0). Host-network gives the container all the host's
        // physical interfaces — typically 2+ on a laptop.
        return nonLoopback > 1 ? EdgeRuntime.DOCKER_HOST_NETWORK : base;
    }

    /**
     * Build the diagnostic {@code runtime_hints} payload that ships
     * alongside the classified runtime. Always returns the 5 keys
     * defined in the brief (with {@code "unavailable"} placeholders
     * where the underlying probe could not read).
     */
    public static Map<String, Object> hints(Probes probes) {
        Map<String, Object> hints = new LinkedHashMap<>();
        String osName = probes.sysProp().apply("os.name");
        hints.put("os_name", osName != null ? osName : "unavailable");
        String javaVersion = probes.sysProp().apply("java.version");
        hints.put("java_version", javaVersion != null ? javaVersion : "unavailable");
        String hostname = probes.env().apply("HOSTNAME");
        hints.put("hostname", hostname != null ? hostname : "unavailable");
        boolean dockerEnv = probes.fileExists().apply(Path.of("/.dockerenv"));
        hints.put("docker_env_file_present", dockerEnv);
        String cgroup = probes.fileRead().apply(Path.of("/proc/1/cgroup"));
        if (cgroup == null) {
            hints.put("cgroup_signature", "unavailable");
        } else {
            // Slice the first line to keep the hint small — full
            // cgroup contents can be noisy and we only need the
            // diagnostic signature.
            int newline = cgroup.indexOf('\n');
            String head = newline > 0 ? cgroup.substring(0, newline) : cgroup;
            hints.put("cgroup_signature", head.length() > 200
                ? head.substring(0, 200) : head);
        }
        return hints;
    }

    /** Production overload — emits hints against the real process. */
    public static Map<String, Object> hints() {
        return hints(Probes.system());
    }
}
