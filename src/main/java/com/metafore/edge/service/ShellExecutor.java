package com.metafore.edge.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.regex.Pattern;

public final class ShellExecutor {

    static final Set<String> ALLOWED_COMMANDS = Set.of(
        "systemctl", "ps", "df", "ss", "cat", "wc"
    );

    private static final Pattern INJECTION_PATTERN = Pattern.compile("[;|&`]|\\$\\(");
    private static final int MAX_LINES = 50;

    private ShellExecutor() {}

    public static boolean isAllowed(String command) {
        if (command == null || command.isBlank()) return false;
        if (INJECTION_PATTERN.matcher(command).find()) return false;
        String firstWord = command.trim().split("\\s+")[0];
        return ALLOWED_COMMANDS.contains(firstWord);
    }

    public static Map<String, Object> execute(String command) {
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.currentTimeMillis();

        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line;
                int lines = 0;
                while ((line = br.readLine()) != null && lines < MAX_LINES) {
                    output.append(line).append("\n");
                    lines++;
                }
            }
            int exitCode = p.waitFor();
            long elapsed = System.currentTimeMillis() - start;

            result.put("status", exitCode == 0 ? "success" : "error");
            result.put("action", "shell");
            result.put("latency_ms", elapsed);
            result.put("data", List.of(Map.of("output", output.toString().trim())));
            if (exitCode != 0) {
                result.put("error", "exit code " + exitCode);
            }
        } catch (Exception e) {
            result.put("status", "error");
            result.put("action", "shell");
            result.put("latency_ms", System.currentTimeMillis() - start);
            result.put("error", e.getMessage());
        }
        return result;
    }
}
