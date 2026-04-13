package com.metafore.edge.service;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public final class DiscoveryService {

    private DiscoveryService() {}

    public static Map<String, Object> defaultScope() {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("discover_os", true);
        scope.put("discover_ports", true);
        scope.put("discover_processes", true);
        scope.put("discover_docker", false);
        scope.put("log_dirs", Collections.singletonList("/var/log/monitored"));
        scope.put("connectivity_targets", Collections.emptyList());
        scope.put("databases", Collections.emptyList());
        return scope;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> execute(String controllerId, Map<String, Object> scope) {
        Map<String, Object> capabilities = new LinkedHashMap<>();

        if (Boolean.TRUE.equals(scope.get("discover_os"))) {
            capabilities.put("os", discoverOS(controllerId));
        } else {
            capabilities.put("os", skipped());
        }

        if (Boolean.TRUE.equals(scope.get("discover_ports"))) {
            capabilities.put("ports", discoverPorts());
        } else {
            capabilities.put("ports", skipped());
        }

        if (Boolean.TRUE.equals(scope.get("discover_processes"))) {
            capabilities.put("processes", discoverProcesses());
        } else {
            capabilities.put("processes", skipped());
        }

        List<Map<String, Object>> dbTargets = (List<Map<String, Object>>)
            scope.getOrDefault("databases", Collections.emptyList());
        if (dbTargets != null && !dbTargets.isEmpty()) {
            capabilities.put("databases", discoverDatabases(dbTargets));
        } else {
            capabilities.put("databases", skipped());
        }

        List<String> targets = (List<String>)
            scope.getOrDefault("connectivity_targets", Collections.emptyList());
        if (targets != null && !targets.isEmpty()) {
            capabilities.put("connectivity", discoverConnectivity(targets));
        } else {
            capabilities.put("connectivity", skipped());
        }

        return capabilities;
    }

    static Map<String, Object> discoverOS(String hostname) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("hostname", hostname);
            data.put("os", System.getProperty("os.name") + " "
                         + System.getProperty("os.version"));
            data.put("cpu_cores", Runtime.getRuntime().availableProcessors());
            data.put("memory_gb", Runtime.getRuntime().maxMemory() / (1024 * 1024 * 1024));

            try {
                String uptime = new String(Files.readAllBytes(
                    Paths.get("/proc/uptime"))).trim().split(" ")[0];
                data.put("uptime_seconds", Double.parseDouble(uptime));
            } catch (Exception ignored) {}

            List<Map<String, String>> ifaces = new ArrayList<>();
            try {
                Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
                while (nets.hasMoreElements()) {
                    NetworkInterface ni = nets.nextElement();
                    if (ni.isLoopback() || !ni.isUp()) continue;
                    Enumeration<InetAddress> addrs = ni.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress addr = addrs.nextElement();
                        if (addr.getHostAddress().contains(":")) continue;
                        Map<String, String> iface = new LinkedHashMap<>();
                        iface.put("name", ni.getName());
                        iface.put("ip", addr.getHostAddress());
                        ifaces.add(iface);
                    }
                }
            } catch (Exception ignored) {}
            data.put("interfaces", ifaces);

            return success(data);
        } catch (Exception e) {
            return failed(e.getMessage());
        }
    }

    static Map<String, Object> discoverPorts() {
        try {
            List<Map<String, Object>> ports = new ArrayList<>();
            for (String proto : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
                try {
                    List<String> lines = Files.readAllLines(Paths.get(proto));
                    for (int i = 1; i < lines.size(); i++) {
                        String[] parts = lines.get(i).trim().split("\\s+");
                        if (parts.length < 4) continue;
                        if (!"0A".equals(parts[3])) continue;
                        String[] addrPort = parts[1].split(":");
                        int port = Integer.parseInt(addrPort[1], 16);
                        if (port == 0) continue;
                        boolean exists = ports.stream().anyMatch(
                            p -> p.get("port").equals(port));
                        if (exists) continue;
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("port", port);
                        entry.put("protocol", labelPort(port));
                        ports.add(entry);
                    }
                } catch (Exception ignored) {}
            }
            return success(ports);
        } catch (Exception e) {
            return failed(e.getMessage());
        }
    }

    static Map<String, Object> discoverProcesses() {
        try {
            List<Map<String, Object>> procs = new ArrayList<>();
            ProcessBuilder pb = new ProcessBuilder("ps", "aux", "--no-headers");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line;
                int count = 0;
                while ((line = br.readLine()) != null && count < 100) {
                    String[] parts = line.trim().split("\\s+", 11);
                    if (parts.length < 11) continue;
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("user", parts[0]);
                    entry.put("pid", Integer.parseInt(parts[1]));
                    entry.put("cpu_pct", Double.parseDouble(parts[2]));
                    entry.put("mem_mb", Double.parseDouble(parts[5]) / 1024);
                    entry.put("name", parts[10].split(" ")[0]);
                    procs.add(entry);
                    count++;
                }
            }
            p.waitFor();
            return success(procs);
        } catch (Exception e) {
            return failed(e.getMessage());
        }
    }

    static Map<String, Object> discoverDatabases(List<Map<String, Object>> targets) {
        List<Map<String, Object>> dbs = new ArrayList<>();
        for (Map<String, Object> target : targets) {
            String ref = String.valueOf(target.getOrDefault("ref", ""));
            String type = String.valueOf(target.getOrDefault("db_type", "unknown"));
            String host = String.valueOf(target.getOrDefault("host", "localhost"));
            String dbName = String.valueOf(target.getOrDefault("db_name", ""));
            int port = 0;
            try {
                port = Integer.parseInt(String.valueOf(target.getOrDefault("port", "0")));
            } catch (Exception ignored) {}

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ref", ref);
            entry.put("type", type);
            entry.put("host", host);
            entry.put("port", port);
            entry.put("db_name", dbName);

            boolean reachable = false;
            if (port > 0) {
                try (Socket sock = new Socket()) {
                    sock.connect(new InetSocketAddress(host, port), 3000);
                    reachable = true;
                } catch (Exception ignored) {}
            }
            entry.put("reachable", reachable);
            dbs.add(entry);
        }
        return success(dbs);
    }

    static Map<String, Object> discoverConnectivity(List<String> targets) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (String target : targets) {
            String[] parts = target.split(":");
            String host = parts[0];
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 80;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("target", target);
            long start = System.currentTimeMillis();
            try (Socket sock = new Socket()) {
                sock.connect(new InetSocketAddress(host, port), 5000);
                entry.put("reachable", true);
                entry.put("latency_ms", System.currentTimeMillis() - start);
            } catch (Exception e) {
                entry.put("reachable", false);
                entry.put("latency_ms", 0);
                entry.put("error", e.getMessage());
            }
            results.add(entry);
        }
        return success(results);
    }

    public static Map<String, Object> success(Object data) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "success");
        r.put("data", data);
        return r;
    }

    public static Map<String, Object> failed(String error) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "failed");
        r.put("error", error);
        return r;
    }

    public static Map<String, Object> skipped() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "skipped");
        return r;
    }

    static String labelPort(int port) {
        return switch (port) {
            case 22   -> "ssh";
            case 80   -> "http";
            case 443  -> "https";
            case 1883 -> "mqtt";
            case 3306 -> "mysql";
            case 5432 -> "postgresql";
            case 5060 -> "sip";
            case 6379 -> "redis";
            case 8080, 8443 -> "http-alt";
            case 9042 -> "cassandra";
            case 9092 -> "kafka";
            case 9200 -> "elasticsearch";
            case 9090 -> "prometheus";
            case 27017 -> "mongodb";
            default   -> "tcp/" + port;
        };
    }
}
