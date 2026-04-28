package com.metafore.edge.service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

public final class SqlExecutor {

    static final Set<String> ALLOWED_SQL_PREFIXES = Set.of(
        "SELECT", "SHOW", "CREATE INDEX", "CREATE TABLE",
        "DELETE FROM", "UPDATE", "INSERT INTO"
    );

    /**
     * Slice 33.1.0 — supported PG types for parametric CRUD payloads.
     * The slice scope is pinned to the column types every existing
     * pack uses today: text/varchar, integer/bigint, numeric (decimal),
     * timestamp/timestamptz, boolean, uuid. JSONB + array (any kind)
     * are explicitly rejected at edge with "unsupported column type"
     * — supporting them requires per-type encoding (PGobject for
     * JSONB; java.sql.Array for arrays) that adds debug surface
     * without a current pack needing it.
     *
     * Future packs that need JSONB / arrays can extend this set as
     * a separate slice with its own test coverage.
     */
    private static final Set<String> SUPPORTED_PG_COLUMN_TYPES = Set.of(
        "text",
        "varchar", "character varying",
        "char", "character",
        "integer", "int", "int4",
        "bigint", "int8",
        "smallint", "int2",
        "numeric", "decimal",
        "real", "float4",
        "double precision", "float8",
        "timestamp without time zone", "timestamp",
        "timestamp with time zone", "timestamptz",
        "date",
        "time without time zone", "time",
        "time with time zone", "timetz",
        "boolean", "bool",
        "uuid"
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

    /**
     * Slice 33.1.0 — parametric CRUD execution via JDBC PreparedStatement.
     *
     * Replaces the legacy ``executeSql + substituteParams`` path for
     * production CRUD. Defense-in-depth: validates table existence
     * and every column's existence + supported type against
     * INFORMATION_SCHEMA on the SAME Connection used for the write,
     * so the schema view is transactionally consistent with the
     * subsequent INSERT/UPDATE/DELETE.
     *
     * Operation contract:
     * - "create": INSERT INTO "table" (cols...) VALUES (?,?,...)
     * - "update": UPDATE "table" SET col=?, col=? WHERE "where_col"=?
     * - "delete": same as update (soft-delete tombstone is an UPDATE
     *   in disguise from edge's perspective; tombstone column names
     *   come through ``columns`` like any other write).
     *
     * Any column the table doesn't have, or any value of an unsupported
     * type, fails the request before any state change. Mirrors the
     * Slice 33.0b lesson — a clear edge-side signal beats a
     * "column does not exist" PG error after partial work.
     */
    public static Map<String, Object> executeParametric(
        DataSource ds,
        String operation,
        String tableName,
        List<String> columns,
        List<Object> values,
        String whereColumn,
        Object whereValue
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.currentTimeMillis();

        // Operation discriminator + payload shape gate.
        if (!Set.of("create", "update", "delete").contains(operation)) {
            return errorResult(start, "execute",
                "Unsupported operation: " + operation
                + " (expected create/update/delete)");
        }
        if (tableName == null || tableName.isBlank()) {
            return errorResult(start, "execute",
                "Missing or empty table_name");
        }
        if (columns == null || values == null
            || columns.size() != values.size()) {
            return errorResult(start, "execute",
                "columns and values must be present and equal length"
                + " (columns=" + (columns == null ? -1 : columns.size())
                + ", values=" + (values == null ? -1 : values.size()) + ")");
        }
        if (("update".equals(operation) || "delete".equals(operation))
            && (whereColumn == null || whereColumn.isBlank())) {
            return errorResult(start, "execute",
                operation + " requires where_column");
        }

        try (Connection conn = ds.getConnection()) {
            // Slice 33.1.0 addition #1: distinct table/column existence
            // checks. Single Connection so the INFORMATION_SCHEMA view
            // is consistent with the subsequent write.
            String validationError = validateTableAndColumns(
                conn, tableName, columns, whereColumn);
            if (validationError != null) {
                return errorResult(start, "execute", validationError);
            }
            // Slice 33.1.0 addition #2: type-supported gate. Reject
            // values whose runtime type isn't in SUPPORTED_PG_COLUMN_TYPES
            // before binding so debug surface stays small.
            String typeError = validateValueTypes(values, whereValue);
            if (typeError != null) {
                return errorResult(start, "execute", typeError);
            }

            String sql = buildSql(operation, tableName, columns, whereColumn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int idx = 1;
                for (Object v : values) {
                    bindValue(ps, idx++, v);
                }
                if ("update".equals(operation) || "delete".equals(operation)) {
                    bindValue(ps, idx, whereValue);
                }
                int affected = ps.executeUpdate();
                result.put("status", "success");
                result.put("action", "execute");
                result.put("latency_ms", System.currentTimeMillis() - start);
                result.put("rows_affected", affected);
                return result;
            }
        } catch (SQLException e) {
            return errorResult(start, "execute", e.getMessage());
        }
    }

    /**
     * Slice 33.1.0 — combined table + column existence check.
     * Returns null on success, or a distinct error message naming
     * the missing table OR the missing column. Two queries, one
     * Connection, transactionally consistent with the caller's write.
     */
    static String validateTableAndColumns(
        Connection conn,
        String tableName,
        List<String> columns,
        String whereColumn
    ) throws SQLException {
        // 1. Table existence — distinct error so "table missing" doesn't
        //    masquerade as "every column missing".
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT 1 FROM information_schema.tables "
            + "WHERE table_schema='public' AND table_name=?"
        )) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return "Table " + tableName + " does not exist";
                }
            }
        }

        // 2. Column existence — pull all known columns once, intersect.
        Set<String> knownCols = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT column_name FROM information_schema.columns "
            + "WHERE table_schema='public' AND table_name=?"
        )) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    knownCols.add(rs.getString(1));
                }
            }
        }

        for (String col : columns) {
            if (col == null || !knownCols.contains(col)) {
                return "Column " + col + " does not exist on table " + tableName;
            }
        }
        if (whereColumn != null && !knownCols.contains(whereColumn)) {
            return "Column " + whereColumn
                + " does not exist on table " + tableName;
        }
        return null;
    }

    /**
     * Slice 33.1.0 — runtime type validation against the supported set.
     * Rejects JSONB/array/PGobject/etc. with a clear message before
     * any DB contact. Keeps debug surface small while a future slice
     * extends the supported types.
     *
     * Returns null on success, or an error message naming the offending
     * value's index and Java type.
     */
    static String validateValueTypes(List<Object> values, Object whereValue) {
        for (int i = 0; i < values.size(); i++) {
            String err = checkSingleValueType(values.get(i), "values[" + i + "]");
            if (err != null) return err;
        }
        return checkSingleValueType(whereValue, "where_value");
    }

    private static String checkSingleValueType(Object v, String label) {
        if (v == null) return null;
        // The supported set covers Java native types JDBC binds
        // directly. Accept the runtime classes that map cleanly:
        if (v instanceof CharSequence) return null;       // text/varchar/uuid (as string)
        if (v instanceof Number) return null;             // int/bigint/numeric/real
        if (v instanceof Boolean) return null;            // boolean
        if (v instanceof java.sql.Timestamp) return null;
        if (v instanceof java.sql.Date) return null;
        if (v instanceof java.sql.Time) return null;
        if (v instanceof java.util.Date) return null;     // generic Date → Timestamp at bind
        // Anything else (List, Map, byte[], PGobject) is unsupported
        // in 33.1.0. Fail clearly, name the type.
        return "Unsupported column type for " + label
            + ": " + v.getClass().getName()
            + ". Supported set: " + String.join(", ", SUPPORTED_PG_COLUMN_TYPES);
    }

    private static String buildSql(
        String operation,
        String tableName,
        List<String> columns,
        String whereColumn
    ) {
        // Identifier quoting via "..." matches the existing renderer's
        // pattern (see metafore-core integration_dispatcher.py renders).
        // Column names came through validateTableAndColumns and are
        // confirmed to exist; identifiers are not user-controlled at
        // this point so quoting + identifier whitelist is sufficient.
        switch (operation) {
            case "create": {
                StringBuilder cols = new StringBuilder();
                StringBuilder qs = new StringBuilder();
                for (int i = 0; i < columns.size(); i++) {
                    if (i > 0) {
                        cols.append(", ");
                        qs.append(", ");
                    }
                    cols.append('"').append(columns.get(i)).append('"');
                    qs.append('?');
                }
                return "INSERT INTO \"" + tableName + "\" ("
                    + cols + ") VALUES (" + qs + ")";
            }
            case "update": {
                StringBuilder set = new StringBuilder();
                for (int i = 0; i < columns.size(); i++) {
                    if (i > 0) set.append(", ");
                    set.append('"').append(columns.get(i)).append("\" = ?");
                }
                return "UPDATE \"" + tableName + "\" SET " + set
                    + " WHERE \"" + whereColumn + "\" = ?";
            }
            case "delete": {
                // Edge sees DELETE as just a parametric UPDATE — soft-
                // delete tombstone columns flow through ``columns`` and
                // ``values`` like any other write. True hard-delete is
                // a future-slice concern (current spec is tombstone).
                StringBuilder set = new StringBuilder();
                for (int i = 0; i < columns.size(); i++) {
                    if (i > 0) set.append(", ");
                    set.append('"').append(columns.get(i)).append("\" = ?");
                }
                return "UPDATE \"" + tableName + "\" SET " + set
                    + " WHERE \"" + whereColumn + "\" = ?";
            }
            default:
                throw new IllegalStateException("buildSql: " + operation);
        }
    }

    private static void bindValue(PreparedStatement ps, int idx, Object v)
        throws SQLException {
        if (v == null) {
            ps.setObject(idx, null);
        } else if (v instanceof CharSequence) {
            ps.setString(idx, v.toString());
        } else if (v instanceof Boolean) {
            ps.setBoolean(idx, (Boolean) v);
        } else if (v instanceof Integer) {
            ps.setInt(idx, (Integer) v);
        } else if (v instanceof Long) {
            ps.setLong(idx, (Long) v);
        } else if (v instanceof Number) {
            // Numeric / decimal / real / double — pass via BigDecimal
            // for fidelity. setObject would also work but BigDecimal
            // preserves scale through JDBC type mapping.
            Number n = (Number) v;
            if (n instanceof java.math.BigDecimal) {
                ps.setBigDecimal(idx, (java.math.BigDecimal) n);
            } else {
                ps.setBigDecimal(idx, java.math.BigDecimal.valueOf(n.doubleValue()));
            }
        } else if (v instanceof java.sql.Timestamp) {
            ps.setTimestamp(idx, (java.sql.Timestamp) v);
        } else if (v instanceof java.sql.Date) {
            ps.setDate(idx, (java.sql.Date) v);
        } else if (v instanceof java.sql.Time) {
            ps.setTime(idx, (java.sql.Time) v);
        } else if (v instanceof java.util.Date) {
            ps.setTimestamp(idx,
                new java.sql.Timestamp(((java.util.Date) v).getTime()));
        } else {
            // validateValueTypes should have caught this; defensive.
            throw new SQLException(
                "bindValue: unsupported runtime type "
                + v.getClass().getName()
                + " — should have been rejected by validateValueTypes");
        }
    }

    private static Map<String, Object> errorResult(
        long start, String action, String message
    ) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "error");
        r.put("action", action);
        r.put("latency_ms", System.currentTimeMillis() - start);
        r.put("error", message);
        return r;
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
