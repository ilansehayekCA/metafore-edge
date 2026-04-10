package com.metafore.edge.service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

public final class SqlExecutor {

    static final Set<String> ALLOWED_SQL_PREFIXES = Set.of(
        "SELECT", "SHOW", "CREATE INDEX", "CREATE TABLE",
        "DELETE FROM", "UPDATE", "INSERT INTO"
    );

    private static final int MAX_ROWS = 100;

    private SqlExecutor() {}

    public static boolean isAllowed(String sql) {
        if (sql == null || sql.isBlank()) return false;
        String upper = sql.trim().toUpperCase();
        return ALLOWED_SQL_PREFIXES.stream().anyMatch(upper::startsWith);
    }

    public static String substituteParams(String sql, Map<String, Object> params) {
        if (sql == null || params == null) return sql;
        String result = sql;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String placeholder = "${" + entry.getKey() + "}";
            if (result.contains(placeholder)) {
                result = result.replace(placeholder, String.valueOf(entry.getValue()));
            }
        }
        return result;
    }

    public static Map<String, Object> execute(DataSource ds, String sql) {
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.currentTimeMillis();

        String upper = sql.trim().toUpperCase();
        boolean isRead = upper.startsWith("SELECT") || upper.startsWith("SHOW");

        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {

            if (isRead) {
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int cols = meta.getColumnCount();
                    List<Map<String, Object>> rows = new ArrayList<>();
                    while (rs.next() && rows.size() < MAX_ROWS) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= cols; i++) {
                            Object val = rs.getObject(i);
                            row.put(meta.getColumnLabel(i), val != null ? val.toString() : null);
                        }
                        rows.add(row);
                    }
                    result.put("status", "success");
                    result.put("action", "query");
                    result.put("latency_ms", System.currentTimeMillis() - start);
                    result.put("row_count", rows.size());
                    result.put("data", rows);
                }
            } else {
                int affected = stmt.executeUpdate(sql);
                result.put("status", "success");
                result.put("action", "execute");
                result.put("latency_ms", System.currentTimeMillis() - start);
                result.put("rows_affected", affected);
            }
        } catch (SQLException e) {
            result.put("status", "error");
            result.put("action", isRead ? "query" : "execute");
            result.put("latency_ms", System.currentTimeMillis() - start);
            result.put("error", e.getMessage());
        }
        return result;
    }
}
