package com.metafore.edge.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 14.9 / ETA.T1 — RuntimeProbe layering, env override, hint
 * shape. Tests drive the probe via the injectable
 * {@link RuntimeProbe.Probes} record so they exercise each layer
 * deterministically without spelunking through the real filesystem.
 */
class RuntimeProbeTest {

    /** Build a Probes instance with all signals controlled. */
    private static RuntimeProbe.Probes probes(
        Map<String, String> env, Map<String, String> sysProps,
        Map<String, Boolean> filePresent, Map<String, String> fileContents
    ) {
        return new RuntimeProbe.Probes(
            k -> env.getOrDefault(k, null),
            k -> sysProps.getOrDefault(k, null),
            p -> filePresent.getOrDefault(p.toString().replace('\\', '/'), false),
            p -> fileContents.getOrDefault(p.toString().replace('\\', '/'), null)
        );
    }

    private static RuntimeProbe.Probes empty() {
        return probes(Map.of(), Map.of(), Map.of(), Map.of());
    }

    // ── (1) Explicit override ────────────────────────────────────────────

    @Test
    void envOverrideDockerReturnsDocker() {
        Map<String, String> env = Map.of("EDGE_RUNTIME", "docker");
        // Other signals present but override wins.
        Map<String, String> sysProps = Map.of("os.name", "Windows 11");
        assertEquals(EdgeRuntime.DOCKER,
            RuntimeProbe.detect(probes(env, sysProps, Map.of(), Map.of())));
    }

    @Test
    void envOverrideCaseInsensitive() {
        Map<String, String> env = Map.of("EDGE_RUNTIME", "Native");
        assertEquals(EdgeRuntime.NATIVE,
            RuntimeProbe.detect(probes(env, Map.of(), Map.of(), Map.of())));
    }

    @Test
    void envOverrideDockerHostNetworkUnderscoreVariant() {
        // Operator typo: DOCKER_HOST_NETWORK. Underscore variant must
        // still resolve.
        Map<String, String> env = Map.of("EDGE_RUNTIME", "DOCKER_HOST_NETWORK");
        assertEquals(EdgeRuntime.DOCKER_HOST_NETWORK,
            RuntimeProbe.detect(probes(env, Map.of(), Map.of(), Map.of())));
    }

    @Test
    void envOverrideGarbageReturnsUnknown() {
        Map<String, String> env = Map.of("EDGE_RUNTIME", "garbage");
        assertEquals(EdgeRuntime.UNKNOWN,
            RuntimeProbe.detect(probes(env, Map.of(), Map.of(), Map.of())));
    }

    @Test
    void envOverrideBlankIgnored() {
        // Empty / whitespace shouldn't be treated as a positive override.
        // Layered probe should continue past stage 1.
        Map<String, String> env = new HashMap<>();
        env.put("EDGE_RUNTIME", "   ");
        Map<String, String> sysProps = Map.of("os.name", "Windows 11");
        assertEquals(EdgeRuntime.NATIVE,
            RuntimeProbe.detect(probes(env, sysProps, Map.of(), Map.of())));
    }

    // ── (2) /.dockerenv ─────────────────────────────────────────────────

    @Test
    void dockerenvFilePresentReturnsDocker() {
        Map<String, Boolean> files = Map.of("/.dockerenv", true);
        assertEquals(EdgeRuntime.DOCKER,
            RuntimeProbe.detect(probes(Map.of(), Map.of(), files, Map.of())));
    }

    // ── (3) /proc/1/cgroup parse ────────────────────────────────────────

    @Test
    void cgroupDockerMarkerReturnsDocker() {
        Map<String, String> contents = Map.of(
            "/proc/1/cgroup", "0::/docker/0123456789abcdef\n"
        );
        assertEquals(EdgeRuntime.DOCKER,
            RuntimeProbe.detect(probes(Map.of(), Map.of(), Map.of(), contents)));
    }

    @Test
    void cgroupKubepodsMarkerReturnsDocker() {
        Map<String, String> contents = Map.of(
            "/proc/1/cgroup", "12:cpu:/kubepods/burstable/pod-uuid\n"
        );
        assertEquals(EdgeRuntime.DOCKER,
            RuntimeProbe.detect(probes(Map.of(), Map.of(), Map.of(), contents)));
    }

    @Test
    void cgroupContainerdMarkerReturnsDocker() {
        Map<String, String> contents = Map.of(
            "/proc/1/cgroup", "0::/system.slice/containerd.service\n"
        );
        assertEquals(EdgeRuntime.DOCKER,
            RuntimeProbe.detect(probes(Map.of(), Map.of(), Map.of(), contents)));
    }

    @Test
    void cgroupNoMarkerSkips() {
        // A plain systemd cgroup means we're not in a container.
        Map<String, String> contents = Map.of(
            "/proc/1/cgroup", "0::/init.scope\n"
        );
        Map<String, String> sysProps = Map.of("os.name", "Linux");
        assertEquals(EdgeRuntime.NATIVE,
            RuntimeProbe.detect(probes(Map.of(), sysProps, Map.of(), contents)));
    }

    // ── (4) HOSTNAME heuristic ──────────────────────────────────────────

    @Test
    void hostnameDockerSignatureWithoutProcReturnsDocker() {
        // /proc unreadable + 12-hex hostname = container default.
        Map<String, String> env = Map.of("HOSTNAME", "0123456789ab");
        assertEquals(EdgeRuntime.DOCKER,
            RuntimeProbe.detect(probes(env, Map.of(), Map.of(), Map.of())));
    }

    @Test
    void hostnameDockerSignatureIgnoredWhenProcAvailable() {
        // /proc is readable + cgroup says "not docker" → ignore the
        // hostname heuristic.
        Map<String, String> env = Map.of("HOSTNAME", "0123456789ab");
        Map<String, String> contents = Map.of(
            "/proc/1/cgroup", "0::/init.scope\n"
        );
        Map<String, String> sysProps = Map.of("os.name", "Linux");
        assertEquals(EdgeRuntime.NATIVE,
            RuntimeProbe.detect(probes(env, sysProps, Map.of(), contents)));
    }

    @Test
    void hostnameNonDockerSignatureSkips() {
        Map<String, String> env = Map.of("HOSTNAME", "my-laptop");
        Map<String, String> sysProps = Map.of("os.name", "Windows 11");
        assertEquals(EdgeRuntime.NATIVE,
            RuntimeProbe.detect(probes(env, sysProps, Map.of(), Map.of())));
    }

    // ── (5) Host-network upgrade ────────────────────────────────────────

    @Test
    void dockerHostNetworkUpgrade() {
        Map<String, Boolean> files = Map.of("/.dockerenv", true);
        // Two non-loopback interfaces → host-network heuristic fires.
        Map<String, String> contents = Map.of(
            "/proc/net/dev",
            "Inter-|   Receive                                                |  Transmit\n"
                + " face |bytes    packets errs drop fifo frame compressed multicast|...\n"
                + "    lo: 0 0 0 0 0 0 0 0 0 0\n"
                + "  eth0: 0 0 0 0 0 0 0 0 0 0\n"
                + "  eth1: 0 0 0 0 0 0 0 0 0 0\n"
        );
        assertEquals(EdgeRuntime.DOCKER_HOST_NETWORK,
            RuntimeProbe.detect(probes(Map.of(), Map.of(), files, contents)));
    }

    @Test
    void dockerSingleInterfaceStaysDocker() {
        Map<String, Boolean> files = Map.of("/.dockerenv", true);
        Map<String, String> contents = Map.of(
            "/proc/net/dev",
            "Inter-|   Receive                                                |  Transmit\n"
                + " face |bytes    packets errs drop fifo frame compressed multicast|...\n"
                + "    lo: 0 0 0 0 0 0 0 0 0 0\n"
                + "  eth0: 0 0 0 0 0 0 0 0 0 0\n"
        );
        assertEquals(EdgeRuntime.DOCKER,
            RuntimeProbe.detect(probes(Map.of(), Map.of(), files, contents)));
    }

    // ── (6) JVM property hint ───────────────────────────────────────────

    @Test
    void osNameWindowsReturnsNative() {
        Map<String, String> sysProps = Map.of("os.name", "Windows 11");
        assertEquals(EdgeRuntime.NATIVE,
            RuntimeProbe.detect(probes(Map.of(), sysProps, Map.of(), Map.of())));
    }

    @Test
    void osNameMacReturnsNative() {
        Map<String, String> sysProps = Map.of("os.name", "Mac OS X");
        assertEquals(EdgeRuntime.NATIVE,
            RuntimeProbe.detect(probes(Map.of(), sysProps, Map.of(), Map.of())));
    }

    @Test
    void osNameLinuxReturnsNative() {
        // Linux with no container markers + no override → NATIVE.
        Map<String, String> sysProps = Map.of("os.name", "Linux");
        assertEquals(EdgeRuntime.NATIVE,
            RuntimeProbe.detect(probes(Map.of(), sysProps, Map.of(), Map.of())));
    }

    // ── (7) Fallthrough ─────────────────────────────────────────────────

    @Test
    void allProbesEmptyReturnsUnknown() {
        assertEquals(EdgeRuntime.UNKNOWN, RuntimeProbe.detect(empty()));
    }

    @Test
    void unknownOsNameReturnsUnknown() {
        // os.name like "FreeBSD" isn't in our positive list.
        Map<String, String> sysProps = Map.of("os.name", "FreeBSD 14.0");
        assertEquals(EdgeRuntime.UNKNOWN,
            RuntimeProbe.detect(probes(Map.of(), sysProps, Map.of(), Map.of())));
    }

    // ── runtime_hints map shape ─────────────────────────────────────────

    @Test
    void hintsAlwaysCarry5Keys() {
        Map<String, Object> hints = RuntimeProbe.hints(empty());
        assertEquals(5, hints.size());
        assertTrue(hints.containsKey("os_name"));
        assertTrue(hints.containsKey("java_version"));
        assertTrue(hints.containsKey("hostname"));
        assertTrue(hints.containsKey("docker_env_file_present"));
        assertTrue(hints.containsKey("cgroup_signature"));
    }

    @Test
    void hintsPopulateFromProbes() {
        Map<String, String> env = Map.of("HOSTNAME", "my-host");
        Map<String, String> sysProps = Map.of(
            "os.name", "Windows 11",
            "java.version", "21.0.2"
        );
        Map<String, Boolean> files = Map.of("/.dockerenv", true);
        Map<String, String> contents = Map.of(
            "/proc/1/cgroup", "0::/docker/0123456789abcdef"
        );
        Map<String, Object> hints = RuntimeProbe.hints(
            probes(env, sysProps, files, contents)
        );
        assertEquals("Windows 11", hints.get("os_name"));
        assertEquals("21.0.2", hints.get("java_version"));
        assertEquals("my-host", hints.get("hostname"));
        assertEquals(Boolean.TRUE, hints.get("docker_env_file_present"));
        assertEquals("0::/docker/0123456789abcdef", hints.get("cgroup_signature"));
    }

    @Test
    void hintsCgroupTruncatedAt200Chars() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append("a");
        }
        Map<String, String> contents = Map.of(
            "/proc/1/cgroup", sb.toString()
        );
        Map<String, Object> hints = RuntimeProbe.hints(
            probes(Map.of(), Map.of(), Map.of(), contents)
        );
        String sig = (String) hints.get("cgroup_signature");
        assertEquals(200, sig.length(),
            "cgroup_signature must be truncated to 200 chars to keep hint payload small");
    }

    @Test
    void hintsUnavailableWhenProbesEmpty() {
        Map<String, Object> hints = RuntimeProbe.hints(empty());
        assertEquals("unavailable", hints.get("os_name"));
        assertEquals("unavailable", hints.get("java_version"));
        assertEquals("unavailable", hints.get("hostname"));
        assertEquals(Boolean.FALSE, hints.get("docker_env_file_present"));
        assertEquals("unavailable", hints.get("cgroup_signature"));
    }

    @Test
    void edgeRuntimeFromWireRoundtrips() {
        assertEquals(EdgeRuntime.DOCKER, EdgeRuntime.fromWire("docker"));
        assertEquals(EdgeRuntime.NATIVE, EdgeRuntime.fromWire("NATIVE"));
        assertEquals(EdgeRuntime.DOCKER_HOST_NETWORK,
            EdgeRuntime.fromWire("docker-host-network"));
        assertEquals(EdgeRuntime.DOCKER_HOST_NETWORK,
            EdgeRuntime.fromWire("DOCKER_HOST_NETWORK"));
        assertEquals(EdgeRuntime.UNKNOWN, EdgeRuntime.fromWire("unknown"));
        assertEquals(EdgeRuntime.UNKNOWN, EdgeRuntime.fromWire(null));
        assertEquals(EdgeRuntime.UNKNOWN, EdgeRuntime.fromWire("garbage"));
    }
}
