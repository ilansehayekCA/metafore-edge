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

    // Absolute page-size ceiling for keyset (``list``) reads. This is
    // DECOUPLED from MAX_ROWS: MAX_ROWS is the legacy per-fetch memory
    // safety net for the non-keyset paths (generic select loop + the
    // by_ids IN-list guard); MAX_PAGE_SIZE is the negotiated pagination
    // ceiling. Conflating the two capped every keyset page at 99 and
    // silently defeated the adapter's declared pagination.read.max_page_size
    // (postgres_managed / postgres_external manifests = 2000). Kept in sync
    // with that manifest value; the effective per-call ceiling is
    // min(requestedLimit, payload "max_page_size" when present, MAX_PAGE_SIZE).
    private static final int MAX_PAGE_SIZE = 2000;

    /** Phase 14.6 / A6 — default schema when ``table_name`` is bare
     *  (unqualified). Matches PG's behaviour for queries without an
     *  explicit ``search_path`` override. */
    static final String DEFAULT_SCHEMA = "public";

    private SqlExecutor() {}

    /**
     * Phase 14.6 / A6 — parsed ``schema.table`` identifier pair.
     *
     * Edge SQL builders historically hardcoded ``table_schema='public'``
     * which broke any external source not living in ``public`` (e.g.
     * the laptop pharma database that hosts ``frm.products``). This
     * record carries the two components from
     * {@link #parseTableName(String)} into the validators + SQL
     * builders so the JDBC-correct ``"schema"."table"`` form is
     * emitted at every call site.
     *
     * Note: parsed schema and table are NOT user-controlled at the
     * SQL-render stage; they pass through {@link #parseTableName}
     * which constrains the format (single dot only) AND through
     * {@link #validateTableAndColumns} which confirms existence in
     * INFORMATION_SCHEMA before any SQL hits the database.
     */
    static final class ParsedTableName {
        final String schema;
        final String table;

        ParsedTableName(String schema, String table) {
            this.schema = schema;
            this.table = table;
        }

        /** Human-readable form for error messages — preserves caller's
         *  ``schema.table`` formatting when explicit, otherwise the
         *  bare table name. */
        String display() {
            return DEFAULT_SCHEMA.equals(schema) && !schemaWasExplicit
                ? table : schema + "." + table;
        }

        /** Tracks whether the caller passed an explicit ``schema.table``
         *  (true) vs a bare ``table`` (false). Used purely by
         *  {@link #display()} so error messages echo the caller's
         *  input shape (avoids surprising the operator who passed
         *  ``patients`` with "Table public.patients does not exist"). */
        boolean schemaWasExplicit;
    }

    /**
     * Phase 14.6 / A6 — split ``table_name`` into ``(schema, table)``.
     *
     * Accepts:
     * <ul>
     *   <li>``patients`` → (public, patients) [legacy bare form]</li>
     *   <li>``frm.products`` → (frm, products) [schema-qualified]</li>
     * </ul>
     *
     * Rejects (throws {@link IllegalArgumentException}):
     * <ul>
     *   <li>multi-dot ``a.b.c`` — ambiguous; PG allows
     *       ``db.schema.table`` but edge has one DB per
     *       DataSource so the form is unsupported</li>
     *   <li>empty schema / table component (``.products``,
     *       ``frm.``)</li>
     *   <li>blank or null input — caller's job to gate, but
     *       defensive here too</li>
     * </ul>
     *
     * Identifier-character validation is intentionally NOT done here —
     * INFORMATION_SCHEMA lookups in
     * {@link #validateTableAndColumns(Connection, ParsedTableName, List, String)}
     * confirm the parsed names refer to a real table before any SQL
     * builder emits them. Identifier quoting via ``"..."`` in
     * {@link #buildSql} / {@link #buildReadSql} is sufficient at the
     * render layer.
     */
    static ParsedTableName parseTableName(String tableName) {
        if (tableName == null) {
            throw new IllegalArgumentException(
                "table_name cannot be null");
        }
        String trimmed = tableName.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(
                "table_name cannot be blank");
        }
        int firstDot = trimmed.indexOf('.');
        if (firstDot < 0) {
            ParsedTableName p = new ParsedTableName(
                DEFAULT_SCHEMA, trimmed);
            p.schemaWasExplicit = false;
            return p;
        }
        int lastDot = trimmed.lastIndexOf('.');
        if (firstDot != lastDot) {
            throw new IllegalArgumentException(
                "table_name has multiple dots — only schema.table form "
                + "is supported (got '" + tableName + "')");
        }
        String schema = trimmed.substring(0, firstDot).trim();
        String table = trimmed.substring(firstDot + 1).trim();
        if (schema.isEmpty()) {
            throw new IllegalArgumentException(
                "table_name schema component is empty (got '"
                + tableName + "')");
        }
        if (table.isEmpty()) {
            throw new IllegalArgumentException(
                "table_name table component is empty (got '"
                + tableName + "')");
        }
        ParsedTableName p = new ParsedTableName(schema, table);
        p.schemaWasExplicit = true;
        return p;
    }

    public static boolean isAllowed(String sql) {
        if (sql == null || sql.isBlank()) return false;
        String upper = sql.trim().toUpperCase();
        return ALLOWED_SQL_PREFIXES.stream().anyMatch(upper::startsWith);
    }

    // security(adr-096): substituteParams(...) — the unescaped
    // ``result.replace("${"+key+"}", String.valueOf(value))`` string
    // interpolation that fed the raw-Statement execute(...) path — has
    // been REMOVED. It was the SQL-injection surface: a param value
    // containing quotes / semicolons / SQL syntax was spliced directly
    // into the SQL text with no escaping, then run via a plain Statement.
    // All SQL now flows through the parametric PreparedStatement path
    // (executeParametric / executeParametricRead), which binds values and
    // never interpolates them. The dispatch boundary in RouteExecutorRoute
    // rejects any raw ``sql`` payload before it can reach the database.

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
     * - "select" (Slice 33.1.0b): SELECT cols FROM "table"
     *   WHERE "where_col" = ?. Returns ``data`` array + ``row_count``
     *   instead of ``rows_affected``. Adds the read half so core's
     *   SELECT-before-UPDATE diff capture (``_persist_update``) can
     *   flip off the legacy string-substitution path in Slice 33.1.1.
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
        boolean isSelect = "select".equals(operation);

        // Operation discriminator + payload shape gate.
        if (!Set.of("create", "update", "delete", "select").contains(operation)) {
            return errorResult(start, "execute",
                "Unsupported operation: " + operation
                + " (expected create/update/delete/select)");
        }
        if (tableName == null || tableName.isBlank()) {
            return errorResult(start, "execute",
                "Missing or empty table_name");
        }

        // Phase 14.6 / A6 — parse ``schema.table`` once and reuse the
        // pair across validator + type-fetch + SQL builder so error
        // messages and INFORMATION_SCHEMA binds stay consistent.
        ParsedTableName parsedTable;
        try {
            parsedTable = parseTableName(tableName);
        } catch (IllegalArgumentException e) {
            return errorResult(start, "execute", e.getMessage());
        }

        // Slice 33.1.0b — ``select`` shape differs from write ops.
        // ``columns`` (when non-empty) is the projection list, NOT bind
        // values; ``values`` is unused. The columns/values length-match
        // gate applies only to write ops.
        if (!isSelect) {
            if (columns == null || values == null
                || columns.size() != values.size()) {
                return errorResult(start, "execute",
                    "columns and values must be present and equal length"
                    + " (columns=" + (columns == null ? -1 : columns.size())
                    + ", values=" + (values == null ? -1 : values.size()) + ")");
            }
        }
        if (("update".equals(operation) || "delete".equals(operation)
                || isSelect)
            && (whereColumn == null || whereColumn.isBlank())) {
            return errorResult(start, "execute",
                operation + " requires where_column");
        }

        try (Connection conn = ds.getConnection()) {
            // Slice 33.1.0 addition #1: distinct table/column existence
            // checks. Single Connection so the INFORMATION_SCHEMA view
            // is consistent with the subsequent write.
            //
            // Slice 33.1.0b — for ``select`` the projection list may be
            // null/empty (caller wants all columns). Skip the per-column
            // existence check in that case; the table existence check
            // still runs, and unknown projection columns surface via
            // PG's own "column does not exist" error if present.
            List<String> colsToValidate = isSelect && (columns == null
                || columns.isEmpty()) ? null : columns;
            String validationError = validateTableAndColumns(
                conn, parsedTable, colsToValidate, whereColumn);
            if (validationError != null) {
                return errorResult(start, "execute", validationError);
            }
            // Slice 33.1.0 addition #2: type-supported gate. Reject
            // values whose runtime type isn't in SUPPORTED_PG_COLUMN_TYPES
            // before binding so debug surface stays small.
            //
            // Slice 33.1.0b — ``select`` only binds ``where_value``;
            // ``values`` is empty so the loop is a no-op anyway, but
            // calling validateValueTypes on a null/empty list is safe.
            String typeError = validateValueTypes(
                isSelect ? java.util.Collections.emptyList() : values,
                whereValue);
            if (typeError != null) {
                return errorResult(start, "execute", typeError);
            }

            // Slice 33.1.0c — pull column types alongside validation so
            // bindValue can route timestamp / date columns through
            // setTimestamp / setDate (PG refuses implicit varchar->
            // timestamptz cast on parameter bindings).
            Map<String, String> columnTypes = fetchColumnTypes(
                conn, parsedTable);

            String sql = buildSql(operation, parsedTable, columns, whereColumn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int idx = 1;
                if (!isSelect) {
                    for (int i = 0; i < values.size(); i++) {
                        String colType = columnTypes.get(columns.get(i));
                        bindValue(ps, idx++, values.get(i), colType);
                    }
                }
                if ("update".equals(operation) || "delete".equals(operation)
                    || isSelect) {
                    String whereType = columnTypes.get(whereColumn);
                    bindValue(ps, idx, whereValue, whereType);
                }
                if (isSelect) {
                    try (ResultSet rs = ps.executeQuery()) {
                        ResultSetMetaData meta = rs.getMetaData();
                        int colCount = meta.getColumnCount();
                        List<Map<String, Object>> rows = new ArrayList<>();
                        while (rs.next() && rows.size() < MAX_ROWS) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            for (int i = 1; i <= colCount; i++) {
                                Object val = rs.getObject(i);
                                row.put(meta.getColumnLabel(i),
                                    val != null ? val.toString() : null);
                            }
                            rows.add(row);
                        }
                        result.put("status", "success");
                        result.put("action", "query");
                        result.put("latency_ms",
                            System.currentTimeMillis() - start);
                        result.put("row_count", rows.size());
                        result.put("data", rows);
                        return result;
                    }
                }
                int affected = ps.executeUpdate();
                result.put("status", "success");
                result.put("action", "execute");
                result.put("latency_ms", System.currentTimeMillis() - start);
                result.put("rows_affected", affected);
                return result;
            }
        } catch (SQLException e) {
            return errorResult(start, isSelect ? "query" : "execute",
                e.getMessage());
        }
    }

    /**
     * Slice 33.1.0 — combined table + column existence check.
     * Returns null on success, or a distinct error message naming
     * the missing table OR the missing column. Two queries, one
     * Connection, transactionally consistent with the caller's write.
     */
    /**
     * Slice 33.1.0c — fetch ``column_name -> data_type`` map for a table.
     *
     * Same INFORMATION_SCHEMA.columns query as
     * {@link #validateTableAndColumns} but returns the type alongside the
     * name. Used by {@link #bindValue} to route timestamp / date columns
     * through ``setTimestamp`` / ``setDate`` after parsing the ISO string,
     * since JDBC ``setString`` on a typed timestamp column does NOT
     * trigger PG's implicit ``varchar -> timestamptz`` cast (that rule
     * applies to text-literal SQL only, not parameter bindings).
     *
     * Single Connection contract: caller passes the same Connection
     * that will subsequently bind + execute the write, so the schema
     * view is transactionally consistent with the write.
     *
     * Returns ``data_type`` from INFORMATION_SCHEMA — long form
     * (``"timestamp with time zone"`` for both ``timestamptz`` and
     * ``timestamp with time zone`` declarations; ``"character varying"``
     * for ``varchar``; etc.).
     */
    static Map<String, String> fetchColumnTypes(
        Connection conn, String tableName
    ) throws SQLException {
        return fetchColumnTypes(conn, parseTableName(tableName));
    }

    /** Phase 14.6 / A6 — schema-aware overload. Existing single-arg
     *  callers route through {@link #parseTableName} so legacy bare
     *  ``patients`` keeps resolving to ``public.patients`` while
     *  ``frm.products`` resolves to the correct external schema. */
    static Map<String, String> fetchColumnTypes(
        Connection conn, ParsedTableName parsed
    ) throws SQLException {
        Map<String, String> types = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT column_name, data_type FROM information_schema.columns "
            + "WHERE table_schema=? AND table_name=?"
        )) {
            ps.setString(1, parsed.schema);
            ps.setString(2, parsed.table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    types.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        return types;
    }

    static String validateTableAndColumns(
        Connection conn,
        String tableName,
        List<String> columns,
        String whereColumn
    ) throws SQLException {
        ParsedTableName parsed;
        try {
            parsed = parseTableName(tableName);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        return validateTableAndColumns(conn, parsed, columns, whereColumn);
    }

    /** Phase 14.6 / A6 — schema-aware overload. The
     *  INFORMATION_SCHEMA lookups now bind both
     *  ``table_schema`` and ``table_name`` from the parsed components
     *  so external sources in non-``public`` schemas pass existence
     *  checks. Error messages echo the caller's original form (e.g.
     *  ``Table frm.products does not exist``) via
     *  {@link ParsedTableName#display()}. */
    static String validateTableAndColumns(
        Connection conn,
        ParsedTableName parsed,
        List<String> columns,
        String whereColumn
    ) throws SQLException {
        // 1. Table existence — distinct error so "table missing" doesn't
        //    masquerade as "every column missing".
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT 1 FROM information_schema.tables "
            + "WHERE table_schema=? AND table_name=?"
        )) {
            ps.setString(1, parsed.schema);
            ps.setString(2, parsed.table);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return "Table " + parsed.display() + " does not exist";
                }
            }
        }

        // 2. Column existence — pull all known columns once, intersect.
        Set<String> knownCols = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT column_name FROM information_schema.columns "
            + "WHERE table_schema=? AND table_name=?"
        )) {
            ps.setString(1, parsed.schema);
            ps.setString(2, parsed.table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    knownCols.add(rs.getString(1));
                }
            }
        }

        // Slice 33.1.0b — caller passes ``columns=null`` when this is a
        // select with no projection list (caller wants ``SELECT *``).
        // Skip the per-column check; the table existence check above
        // still runs, and the where_column check below still runs.
        if (columns != null) {
            for (String col : columns) {
                if (col == null || !knownCols.contains(col)) {
                    return "Column " + col + " does not exist on table "
                        + parsed.display();
                }
            }
        }
        if (whereColumn != null && !knownCols.contains(whereColumn)) {
            return "Column " + whereColumn
                + " does not exist on table " + parsed.display();
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

    /** Phase 14.6 / A6 — render the JDBC-correct identifier for a
     *  table. When the caller passed an explicit ``schema.table``
     *  (e.g. ``frm.products``), emit ``"schema"."table"``. When the
     *  caller passed a bare ``table`` (e.g. ``patients`` — the
     *  pre-14.6 default), emit just ``"table"`` to preserve byte-for-
     *  byte SQL output for existing managed_pg dispatch sites.
     *
     *  Either form is JDBC-valid; PG resolves the unqualified form via
     *  the connection's ``search_path`` (which defaults to
     *  ``"$user", public``). The managed AC sessions always live in
     *  the default search_path, so the legacy bare emission keeps
     *  finding ``public.patients`` exactly as before. */
    static String renderQualifiedTable(ParsedTableName parsed) {
        if (parsed.schemaWasExplicit) {
            return "\"" + parsed.schema + "\".\"" + parsed.table + "\"";
        }
        return "\"" + parsed.table + "\"";
    }

    static String buildSql(
        String operation,
        String tableName,
        List<String> columns,
        String whereColumn
    ) {
        return buildSql(operation, parseTableName(tableName),
            columns, whereColumn);
    }

    /**
     * Phase 14.6 / A6 — schema-aware overload.
     *
     * Identifier quoting via ``"schema"."table"`` matches the JDBC /
     * PG convention for qualified table names. Column names came
     * through validateTableAndColumns and are confirmed to exist;
     * identifiers are not user-controlled at this point so the
     * combination of explicit ``"..."`` quoting + INFORMATION_SCHEMA
     * existence-check is the same safety surface the unqualified
     * legacy path always relied on.
     */
    static String buildSql(
        String operation,
        ParsedTableName parsed,
        List<String> columns,
        String whereColumn
    ) {
        String qualifiedTable = renderQualifiedTable(parsed);
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
                return "INSERT INTO " + qualifiedTable + " ("
                    + cols + ") VALUES (" + qs + ")";
            }
            case "update": {
                StringBuilder set = new StringBuilder();
                for (int i = 0; i < columns.size(); i++) {
                    if (i > 0) set.append(", ");
                    set.append('"').append(columns.get(i)).append("\" = ?");
                }
                return "UPDATE " + qualifiedTable + " SET " + set
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
                return "UPDATE " + qualifiedTable + " SET " + set
                    + " WHERE \"" + whereColumn + "\" = ?";
            }
            case "select": {
                // Slice 33.1.0b — projection list comes through ``columns``;
                // null/empty means SELECT *. ``where_column`` is required
                // (gated upstream); only one bind value (where_value).
                StringBuilder proj = new StringBuilder();
                if (columns == null || columns.isEmpty()) {
                    proj.append("*");
                } else {
                    for (int i = 0; i < columns.size(); i++) {
                        if (i > 0) proj.append(", ");
                        proj.append('"').append(columns.get(i)).append('"');
                    }
                }
                return "SELECT " + proj + " FROM " + qualifiedTable + " "
                    + "WHERE \"" + whereColumn + "\" = ?";
            }
            default:
                throw new IllegalStateException("buildSql: " + operation);
        }
    }

    /**
     * Slice 33.1.0c — column-type-aware bind.
     *
     * When ``columnType`` identifies a timestamp / date column AND the
     * value is an ISO string, parses + binds via ``setTimestamp`` /
     * ``setDate`` so PG accepts the parameter. Plain ``setString`` on a
     * timestamp column FAILS in PG (no implicit varchar->timestamptz
     * cast at the bind layer; that rule is for SQL text literals only).
     *
     * INFORMATION_SCHEMA returns the long form ``"timestamp with time
     * zone"`` for both ``timestamptz`` and ``timestamp with time zone``
     * declarations — single contains-check covers the family.
     *
     * Backwards compatibility: ``columnType=null`` (or unknown / non-
     * timestamp) falls through to the legacy ``instanceof`` chain so
     * non-timestamp columns + already-typed values keep working.
     */
    static void bindValue(
        PreparedStatement ps, int idx, Object v, String columnType
    ) throws SQLException {
        if (v instanceof CharSequence && columnType != null) {
            String s = v.toString();
            if (columnType.equals("date")) {
                try {
                    ps.setDate(idx, java.sql.Date.valueOf(
                        java.time.LocalDate.parse(s)));
                } catch (java.time.format.DateTimeParseException e) {
                    throw new SQLException(
                        "Invalid ISO date string for column type 'date': "
                        + s + " (" + e.getMessage() + ")", e);
                }
                return;
            }
            if (columnType.contains("with time zone")) {
                try {
                    ps.setTimestamp(idx, java.sql.Timestamp.from(
                        java.time.OffsetDateTime.parse(s).toInstant()));
                } catch (java.time.format.DateTimeParseException e) {
                    throw new SQLException(
                        "Invalid ISO timestamptz string for column type "
                        + "'" + columnType + "': " + s
                        + " (" + e.getMessage() + ")", e);
                }
                return;
            }
            if (columnType.contains("timestamp")) {
                // ``timestamp without time zone`` — accept naive ISO.
                try {
                    ps.setTimestamp(idx, java.sql.Timestamp.valueOf(
                        java.time.LocalDateTime.parse(s)));
                } catch (java.time.format.DateTimeParseException e) {
                    throw new SQLException(
                        "Invalid ISO timestamp string for column type "
                        + "'" + columnType + "': " + s
                        + " (" + e.getMessage() + ")", e);
                }
                return;
            }
        }
        bindValueLegacy(ps, idx, v);
    }

    /**
     * Legacy bind path — preserved for backwards compatibility when
     * column type is null/unknown OR the value is already a typed
     * Java object (Timestamp, Date, Number, etc.). ``executeSql``
     * doesn't go through ``bindValue`` at all, so this path is only
     * reached from ``executeParametric``.
     */
    private static void bindValueLegacy(
        PreparedStatement ps, int idx, Object v
    ) throws SQLException {
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

    /**
     * ADR-016 F1.T2b — parametric on-demand record reads through the
     * Integration's routes.read template.
     *
     * Distinct from {@link #executeParametric}'s ``select`` op (Slice
     * 33.1.0b, single-row by where_column = where_value used for
     * SELECT-before-UPDATE): the new ``read`` operation supports four
     * patterns the dispatcher chooses based on the caller's need —
     * by_id, by_ids (IN clause), list (with optional FK filter +
     * tombstone exclusion), count (aggregate). All four go through
     * the same PreparedStatement + INFORMATION_SCHEMA validation +
     * type-supported gate as the write ops, so the safety surface
     * stays uniform across CRUD.
     *
     * Payload shape:
     * <pre>
     * {
     *   operation:           "read",
     *   pattern:             "by_id" | "by_ids" | "list" | "count",
     *   table_name:          STRING,
     *   columns:             [STRING] | null,      // projection; null/empty = SELECT *
     *                                              // (ignored for count)
     *   filter_column:       STRING | null,        // FK or key column
     *   filter_value:        Object | null,        // for by_id; for list/count with FK
     *   filter_values:       [Object] | null,      // for by_ids
     *   exclude_tombstoned:  BOOL,                 // AND tombstoned_at IS NULL
     *
     *   // Phase 13 / REK.T2a — keyset pagination (list pattern only):
     *   keyset_field:        STRING | null,        // OPTIONAL — when present, enables keyset mode
     *   keyset_value:        Object | null,        // OPTIONAL when keyset_field is set; cursor
     *                                              //   pivot (WHERE "$keyset_field" > ?).
     *                                              //   null/missing means "start from beginning"
     *                                              //   (no WHERE clause on the keyset column).
     *   max_page_size:       INT | null            // OPTIONAL — adapter's declared page ceiling
     *                                              //   (pagination.read.max_page_size). Caps the
     *                                              //   effective limit; falls back to MAX_PAGE_SIZE.
     *   limit:               INT | null            // REQUIRED when keyset_field is set; page size.
     *                                              //   Clamped to min(requestedLimit, max_page_size,
     *                                              //   MAX_PAGE_SIZE) — NOT MAX_ROWS. Edge fetches
     *                                              //   limit+1 rows internally to derive has_more.
     * }
     * </pre>
     *
     * Pattern → SQL:
     * <ul>
     *   <li>by_id   → SELECT cols FROM "t" WHERE "fc" = ?</li>
     *   <li>by_ids  → SELECT cols FROM "t" WHERE "fc" IN (?,?,…)</li>
     *   <li>list    → SELECT cols FROM "t" [WHERE "fc"=? [AND]]
     *                 [tombstoned_at IS NULL]</li>
     *   <li>list (keyset) → SELECT cols FROM "t"
     *                 [WHERE "$keyset_field" > ?]
     *                 [AND "fc"=?] [AND "tombstoned_at" IS NULL]
     *                 ORDER BY "$keyset_field" ASC LIMIT ?</li>
     *   <li>count   → SELECT COUNT(*) AS count FROM "t" [WHERE …]</li>
     * </ul>
     *
     * Returns the same envelope as the existing ``select`` op:
     * <pre>
     * {status, action: "query", latency_ms, row_count, data: [...]}
     * </pre>
     * count pattern returns one row in data: <code>[{count: N}]</code>
     * with row_count=1.
     *
     * Keyset mode adds two fields to the success envelope:
     * <pre>
     * {..., has_more: BOOL, last_keyset_value: Object | null}
     * </pre>
     * ``has_more`` is true iff the internal fetch returned more than
     * ``limit`` rows (the sentinel +1 row is trimmed before serializing).
     * ``last_keyset_value`` is the keyset_field value of the last row
     * returned to the caller (after trimming) when ``has_more`` is true;
     * null otherwise. Callers use ``last_keyset_value`` as the next
     * ``keyset_value`` to fetch the next page.
     *
     * Backward compatibility: when ``keyset_field`` is absent from the
     * payload, the SQL emission + envelope is BYTE-FOR-BYTE identical to
     * the pre-REK.T2a behavior — existing dispatchers see no change.
     *
     * Validation order:
     *  1. Pattern + table_name presence.
     *  2. Pattern-specific required fields (filter_column / filter_value /
     *     filter_values).
     *  3. Keyset-mode preconditions: pattern must be ``list``; ``limit``
     *     must be present + positive. ``keyset_field`` existence is
     *     checked alongside the rest of the validation columns in step 4.
     *  4. Table existence + projection column existence (skipped for
     *     count) + filter_column existence + tombstoned_at column
     *     existence (when exclude_tombstoned=true) + keyset_field
     *     existence (when keyset mode).
     *  5. Runtime type-supported gate on filter values (and keyset_value
     *     when present).
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> executeParametricRead(
        DataSource ds, Map<String, Object> payload
    ) {
        long start = System.currentTimeMillis();

        if (payload == null) {
            return errorResult(start, "query", "Missing read payload");
        }
        String pattern = (String) payload.get("pattern");
        String tableName = (String) payload.get("table_name");
        Object colsObj = payload.get("columns");
        String filterColumn = (String) payload.get("filter_column");
        Object filterValue = payload.get("filter_value");
        Object filterValuesObj = payload.get("filter_values");
        boolean excludeTombstoned = Boolean.TRUE.equals(
            payload.get("exclude_tombstoned"));

        // Phase 13 / REK.T2a — keyset pagination fields.
        // keyset_field absent → behave exactly as pre-REK.T2a (back-compat).
        String keysetField = (String) payload.get("keyset_field");
        Object keysetValue = payload.get("keyset_value");
        Object limitObj = payload.get("limit");
        boolean keysetMode = keysetField != null && !keysetField.isBlank();

        if (pattern == null || !Set.of(
                "by_id", "by_ids", "list", "count").contains(pattern)) {
            return errorResult(start, "query",
                "Unsupported read pattern: " + pattern
                + " (expected by_id/by_ids/list/count)");
        }
        if (tableName == null || tableName.isBlank()) {
            return errorResult(start, "query",
                "Missing or empty table_name");
        }

        // ADR-077 — optional abstract-predicate pushdown. Parsed early so a
        // malformed predicate or unsupported op fails before any DB contact.
        // Supported for list/count only (filter semantics).
        List<WhereCond> whereConds;
        try {
            whereConds = parseWhereConditions(payload.get("where_conditions"));
        } catch (IllegalArgumentException e) {
            return errorResult(start, "query", e.getMessage());
        }
        if (!whereConds.isEmpty()
            && !("list".equals(pattern) || "count".equals(pattern))) {
            return errorResult(start, "query",
                "where_conditions is only supported for list/count patterns "
                + "(got pattern=" + pattern + ")");
        }

        // Phase 14.6 / A6 — parse once for the read path too.
        ParsedTableName parsedTable;
        try {
            parsedTable = parseTableName(tableName);
        } catch (IllegalArgumentException e) {
            return errorResult(start, "query", e.getMessage());
        }

        // Keyset-mode preconditions. Keyset semantics are list-only —
        // ORDER BY + LIMIT on by_id / by_ids / count would either be a
        // contradiction (by_id returns ≤1) or change scalar semantics
        // (count). Reject loudly so misuse surfaces at the edge.
        int effectiveLimit = 0;
        if (keysetMode) {
            if (!"list".equals(pattern)) {
                return errorResult(start, "query",
                    "keyset_field only supported with pattern=list "
                    + "(got pattern=" + pattern + ")");
            }
            if (limitObj == null) {
                return errorResult(start, "query",
                    "keyset_field requires limit");
            }
            if (!(limitObj instanceof Number)) {
                return errorResult(start, "query",
                    "limit must be a number (got "
                    + limitObj.getClass().getName() + ")");
            }
            int requestedLimit = ((Number) limitObj).intValue();
            if (requestedLimit <= 0) {
                return errorResult(start, "query",
                    "limit must be positive (got " + requestedLimit + ")");
            }
            // Page size is bounded by the adapter's declared ceiling
            // (payload "max_page_size", forwarded by the dispatcher), then
            // by the edge's absolute MAX_PAGE_SIZE safety ceiling — NOT by
            // MAX_ROWS. The has_more sentinel (limit+1) is honored by
            // fetchCap below, which uses effectiveLimit+1 in keyset mode.
            int pageCeiling = MAX_PAGE_SIZE;
            Object maxPageSizeObj = payload.get("max_page_size");
            if (maxPageSizeObj instanceof Number) {
                int declared = ((Number) maxPageSizeObj).intValue();
                if (declared > 0) {
                    pageCeiling = Math.min(pageCeiling, declared);
                }
            }
            effectiveLimit = Math.min(requestedLimit, pageCeiling);
        }

        // Pattern-specific required fields.
        List<Object> filterValues = null;
        if ("by_id".equals(pattern)) {
            if (filterColumn == null || filterColumn.isBlank()) {
                return errorResult(start, "query",
                    "by_id pattern requires filter_column");
            }
            if (filterValue == null) {
                return errorResult(start, "query",
                    "by_id pattern requires filter_value");
            }
        } else if ("by_ids".equals(pattern)) {
            if (filterColumn == null || filterColumn.isBlank()) {
                return errorResult(start, "query",
                    "by_ids pattern requires filter_column");
            }
            if (!(filterValuesObj instanceof List)) {
                return errorResult(start, "query",
                    "by_ids pattern requires filter_values list");
            }
            filterValues = new ArrayList<>((List<Object>) filterValuesObj);
            if (filterValues.isEmpty()) {
                return errorResult(start, "query",
                    "by_ids pattern requires non-empty filter_values");
            }
            if (filterValues.size() > MAX_ROWS) {
                return errorResult(start, "query",
                    "by_ids filter_values exceeds MAX_ROWS=" + MAX_ROWS
                    + " (size=" + filterValues.size() + ")");
            }
        } else {
            // list / count — filter_column is optional; if present,
            // filter_value is required.
            if (filterColumn != null && !filterColumn.isBlank()
                && filterValue == null) {
                return errorResult(start, "query",
                    pattern + " pattern with filter_column requires "
                    + "filter_value");
            }
        }

        // Normalize projection columns. Count ignores projection.
        List<String> columns;
        if ("count".equals(pattern)) {
            columns = java.util.Collections.emptyList();
        } else if (colsObj instanceof List) {
            columns = new ArrayList<>();
            for (Object c : (List<Object>) colsObj) {
                if (c != null) columns.add(c.toString());
            }
        } else {
            columns = java.util.Collections.emptyList();
        }

        try (Connection conn = ds.getConnection()) {
            // Build the validation column list: projection cols
            // (skipped for count) + tombstoned_at when exclude_tombstoned
            // + keyset_field when in keyset mode.
            List<String> colsToValidate;
            if ("count".equals(pattern) && !excludeTombstoned && !keysetMode
                && whereConds.isEmpty()) {
                colsToValidate = null;  // skip per-column check
            } else {
                List<String> cv = new ArrayList<>();
                if (!"count".equals(pattern)) cv.addAll(columns);
                if (excludeTombstoned) cv.add("tombstoned_at");
                if (keysetMode) cv.add(keysetField);
                // ADR-077 — predicate columns must exist before we quote
                // them into the WHERE clause.
                for (WhereCond c : whereConds) cv.add(c.column);
                colsToValidate = cv.isEmpty() ? null : cv;
            }
            String validationError = validateTableAndColumns(
                conn, parsedTable, colsToValidate, filterColumn);
            if (validationError != null) {
                return errorResult(start, "query", validationError);
            }

            // Type-supported gate on filter values + keyset_value.
            String typeError;
            if ("by_ids".equals(pattern)) {
                typeError = validateValueTypes(filterValues, null);
            } else if (filterValue != null) {
                typeError = validateValueTypes(
                    java.util.Collections.emptyList(), filterValue);
            } else {
                typeError = null;
            }
            if (typeError == null && keysetMode && keysetValue != null) {
                // Use the where_value slot (label="where_value") to surface
                // a clean diagnostic; the JSONB/array rejection set is
                // the same for keyset_value as for any other bound value.
                typeError = validateValueTypes(
                    java.util.Collections.emptyList(), keysetValue);
            }
            if (typeError == null && !whereConds.isEmpty()) {
                // ADR-077 — type-gate the flattened predicate binds (the
                // same JSONB/array rejection set as filter/keyset values).
                typeError = validateValueTypes(
                    collectWhereBinds(whereConds), null);
            }
            if (typeError != null) {
                return errorResult(start, "query", typeError);
            }

            Map<String, String> columnTypes = fetchColumnTypes(
                conn, parsedTable);

            String sql = buildReadSql(
                pattern, parsedTable, columns, filterColumn,
                filterValues != null ? filterValues.size() : 0,
                excludeTombstoned,
                keysetMode ? keysetField : null,
                keysetMode && keysetValue != null,
                whereConds
            );

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int idx = 1;
                // Bind order MUST match the WHERE-clause emission order in
                // buildReadSql: keyset comparator first (only when
                // keyset_value present), then filter values, then LIMIT.
                if (keysetMode && keysetValue != null) {
                    String ksType = columnTypes.get(keysetField);
                    bindValue(ps, idx++, keysetValue, ksType);
                }
                if ("by_ids".equals(pattern)) {
                    String fcType = columnTypes.get(filterColumn);
                    for (Object v : filterValues) {
                        bindValue(ps, idx++, v, fcType);
                    }
                } else if (filterValue != null && filterColumn != null
                    && !filterColumn.isBlank()) {
                    String fcType = columnTypes.get(filterColumn);
                    bindValue(ps, idx++, filterValue, fcType);
                }
                // ADR-077 — bind predicate values AFTER the filter and
                // BEFORE the LIMIT, matching renderWhereFragment order.
                // Per-condition column type drives ISO date/timestamp
                // coercion (e.g. "born in 1950" → date BETWEEN ? AND ?).
                for (WhereCond c : whereConds) {
                    String ct = columnTypes.get(c.column);
                    if ("is_null".equals(c.op) || "is_not_null".equals(c.op)) {
                        continue;
                    } else if ("between".equals(c.op)) {
                        List<?> lh = (List<?>) c.value;
                        bindValue(ps, idx++, lh.get(0), ct);
                        bindValue(ps, idx++, lh.get(1), ct);
                    } else if ("in".equals(c.op) || "not_in".equals(c.op)) {
                        for (Object el : (List<?>) c.value) {
                            bindValue(ps, idx++, el, ct);
                        }
                    } else if ("contains".equals(c.op)) {
                        bindValue(ps, idx++, "%" + c.value + "%", ct);
                    } else {
                        bindValue(ps, idx++, c.value, ct);
                    }
                }
                if (keysetMode) {
                    // LIMIT placeholder gets limit+1 to derive has_more.
                    ps.setInt(idx++, effectiveLimit + 1);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    // Identify which projection index corresponds to
                    // keyset_field so we can pull last_keyset_value
                    // without re-fetching. -1 means not in projection
                    // (caller asked for cols that don't include it);
                    // we still trim correctly but cannot surface
                    // last_keyset_value — caller should include
                    // keyset_field in projection in that case.
                    int keysetColIdx = -1;
                    if (keysetMode) {
                        for (int i = 1; i <= colCount; i++) {
                            if (keysetField.equals(meta.getColumnLabel(i))) {
                                keysetColIdx = i;
                                break;
                            }
                        }
                    }
                    List<Map<String, Object>> rows = new ArrayList<>();
                    // Pre-keyset cap (MAX_ROWS) is unchanged. Keyset mode
                    // caps at effectiveLimit+1 (which is <= MAX_ROWS by
                    // construction), so the same MAX_ROWS check is a no-op
                    // for keyset reads but stays a defensive ceiling.
                    int fetchCap = keysetMode
                        ? effectiveLimit + 1 : MAX_ROWS;
                    Object lastKeysetValueRaw = null;
                    while (rs.next() && rows.size() < fetchCap) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= colCount; i++) {
                            Object val = rs.getObject(i);
                            if (keysetMode && i == keysetColIdx) {
                                lastKeysetValueRaw = val;
                            }
                            row.put(meta.getColumnLabel(i),
                                val != null ? val.toString() : null);
                        }
                        rows.add(row);
                    }
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", "success");
                    result.put("action", "query");
                    result.put("latency_ms",
                        System.currentTimeMillis() - start);
                    if (keysetMode) {
                        boolean hasMore = rows.size() > effectiveLimit;
                        if (hasMore) {
                            // Trim the +1 sentinel so callers always
                            // see exactly limit rows.
                            rows = rows.subList(0, effectiveLimit);
                        }
                        // last_keyset_value: keyset_field value of the
                        // last row IN THE TRIMMED PAGE when has_more is
                        // true (so the caller can paginate forward).
                        // Null when has_more=false (no next page).
                        Object lastKv = null;
                        if (hasMore && !rows.isEmpty() && keysetColIdx > 0) {
                            Object lastVal = rows.get(rows.size() - 1)
                                .get(keysetField);
                            lastKv = lastVal;
                        }
                        result.put("row_count", rows.size());
                        result.put("data", rows);
                        result.put("has_more", hasMore);
                        result.put("last_keyset_value", lastKv);
                    } else {
                        result.put("row_count", rows.size());
                        result.put("data", rows);
                    }
                    return result;
                }
            }
        } catch (SQLException e) {
            return errorResult(start, "query", e.getMessage());
        }
    }

    /**
     * ADR-016 F1.T2b — build the SELECT SQL for a read pattern.
     * Package-private so the four pattern variants are directly
     * testable without a Connection.
     *
     * Identifier quoting is via "..." matching the write-side
     * renderers; identifier names are not user-controlled and have
     * been checked by {@link #validateTableAndColumns} upstream.
     *
     * Legacy 6-arg overload — preserved BYTE-FOR-BYTE for backwards
     * compatibility with the existing test suite and any direct
     * callers. Delegates to the 8-arg keyset-aware overload with
     * keyset_field=null (which short-circuits the keyset emission
     * branches so the output is identical to today's).
     */
    // ── ADR-077 — abstract-predicate pushdown ─────────────────────────

    /** Operators the edge can render as parameterized SQL. MUST match the
     *  platform's abstract-predicate vocabulary (record_actions._WHERE_OPS).
     *  Any op outside this set is rejected at executeParametricRead — the
     *  edge never interpolates an unknown operator into SQL. */
    static final Set<String> SUPPORTED_WHERE_OPS = Set.of(
        "eq", "ne", "in", "not_in", "gt", "gte", "lt", "lte",
        "between", "contains", "is_null", "is_not_null"
    );

    /** One parsed abstract-predicate condition for pushdown (ADR-077).
     *  ``column`` is validated against INFORMATION_SCHEMA before any SQL is
     *  built; ``value`` is bound via PreparedStatement — never interpolated.
     *  ``value`` is a scalar (eq/ne/gt/…/contains), a 2-element list
     *  (between), an N-element list (in/not_in), or null (is_null/
     *  is_not_null). */
    static final class WhereCond {
        final String column;
        final String op;
        final Object value;

        WhereCond(String column, String op, Object value) {
            this.column = column;
            this.op = op;
            this.value = value;
        }
    }

    /** Parse the optional ``where_conditions`` payload list into WhereCond
     *  objects. Accepts ``column`` or ``field`` as the column key (the
     *  platform's abstract predicate uses ``field``). Returns an empty list
     *  when the payload is absent; throws IllegalArgumentException on a
     *  malformed entry or unsupported op so executeParametricRead can
     *  surface a clean error (never a half-built SQL string). */
    @SuppressWarnings("unchecked")
    static List<WhereCond> parseWhereConditions(Object raw) {
        List<WhereCond> out = new ArrayList<>();
        if (raw == null) return out;
        if (!(raw instanceof List)) {
            throw new IllegalArgumentException(
                "where_conditions must be a list");
        }
        for (Object o : (List<Object>) raw) {
            if (!(o instanceof Map)) {
                throw new IllegalArgumentException(
                    "where_conditions entry must be an object");
            }
            Map<String, Object> m = (Map<String, Object>) o;
            Object colObj = m.containsKey("column")
                ? m.get("column") : m.get("field");
            String column = colObj == null ? null : colObj.toString();
            String op = m.get("op") == null ? null : m.get("op").toString();
            if (column == null || column.isBlank()) {
                throw new IllegalArgumentException(
                    "where condition missing column/field");
            }
            if (op == null || !SUPPORTED_WHERE_OPS.contains(op)) {
                throw new IllegalArgumentException(
                    "unsupported where op: " + op);
            }
            Object value = m.get("value");
            if (("in".equals(op) || "not_in".equals(op))) {
                if (!(value instanceof List)
                    || ((List<?>) value).isEmpty()) {
                    throw new IllegalArgumentException(
                        op + " requires a non-empty value list");
                }
            } else if ("between".equals(op)) {
                if (!(value instanceof List)
                    || ((List<?>) value).size() != 2) {
                    throw new IllegalArgumentException(
                        "between requires a [low, high] value list");
                }
            } else if (!"is_null".equals(op) && !"is_not_null".equals(op)
                && value == null) {
                throw new IllegalArgumentException(
                    op + " requires a value");
            }
            out.add(new WhereCond(column, op, value));
        }
        return out;
    }

    /** Render one WhereCond as a parameterized SQL fragment (column quoted,
     *  values as ``?`` placeholders). Bind order is the order returned by
     *  {@link #collectWhereBinds}. */
    static String renderWhereFragment(WhereCond c) {
        String col = "\"" + c.column + "\"";
        switch (c.op) {
            case "eq":       return col + " = ?";
            case "ne":       return col + " <> ?";
            case "gt":       return col + " > ?";
            case "gte":      return col + " >= ?";
            case "lt":       return col + " < ?";
            case "lte":      return col + " <= ?";
            case "between":  return col + " BETWEEN ? AND ?";
            case "contains": return col + " ILIKE ?";
            case "is_null":     return col + " IS NULL";
            case "is_not_null": return col + " IS NOT NULL";
            case "in":
            case "not_in": {
                int n = ((List<?>) c.value).size();
                StringBuilder sb = new StringBuilder(col);
                sb.append("not_in".equals(c.op) ? " NOT IN (" : " IN (");
                for (int i = 0; i < n; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append('?');
                }
                sb.append(')');
                return sb.toString();
            }
            default:
                // Unreachable: parseWhereConditions allowlists ops.
                throw new IllegalArgumentException(
                    "unsupported where op: " + c.op);
        }
    }

    /** Ordered scalar bind values for a WhereCond list, matching the
     *  emission order of {@link #renderWhereFragment}. is_null/is_not_null
     *  contribute none; between contributes [low, high]; in/not_in
     *  contribute each element; contains contributes the %-wrapped term;
     *  everything else contributes the single value. Used both for the
     *  pre-flight type gate and for the actual bind loop. */
    static List<Object> collectWhereBinds(List<WhereCond> conds) {
        List<Object> binds = new ArrayList<>();
        for (WhereCond c : conds) {
            switch (c.op) {
                case "is_null":
                case "is_not_null":
                    break;
                case "between":
                    binds.add(((List<?>) c.value).get(0));
                    binds.add(((List<?>) c.value).get(1));
                    break;
                case "in":
                case "not_in":
                    binds.addAll((List<Object>) c.value);
                    break;
                case "contains":
                    binds.add("%" + c.value + "%");
                    break;
                default:
                    binds.add(c.value);
            }
        }
        return binds;
    }

    static String buildReadSql(
        String pattern, String tableName, List<String> columns,
        String filterColumn, int filterValuesCount,
        boolean excludeTombstoned
    ) {
        return buildReadSql(
            pattern, tableName, columns, filterColumn, filterValuesCount,
            excludeTombstoned, null, false
        );
    }

    /**
     * Phase 13 / REK.T2a — build the SELECT SQL for a read pattern with
     * optional keyset pagination semantics.
     *
     * When ``keysetField`` is null, the emitted SQL is BYTE-FOR-BYTE
     * IDENTICAL to the pre-REK.T2a overload's output. Existing
     * dispatchers see no change.
     *
     * When ``keysetField`` is non-null AND ``keysetValuePresent`` is
     * true, the WHERE clause gains ``"$keysetField" > ?`` as the
     * FIRST predicate (so binds line up: keyset comparator first,
     * then any filter binds, then the LIMIT bind). When
     * ``keysetValuePresent`` is false (first-page fetch with no
     * cursor pivot), the comparator is omitted but ORDER BY + LIMIT
     * are still emitted.
     *
     * Keyset semantics imply ``ORDER BY "$keysetField" ASC LIMIT ?``
     * appended after the WHERE block. The LIMIT placeholder is bound
     * to ``effectiveLimit + 1`` by the caller (sentinel row for
     * has_more derivation).
     *
     * Keyset semantics are LIST-ONLY — the caller is expected to
     * reject keyset for by_id / by_ids / count upstream. This builder
     * does not double-check; if a caller misuses it, the emitted SQL
     * is wrong but no injection or column-existence violation occurs.
     */
    static String buildReadSql(
        String pattern, String tableName, List<String> columns,
        String filterColumn, int filterValuesCount,
        boolean excludeTombstoned,
        String keysetField, boolean keysetValuePresent
    ) {
        return buildReadSql(pattern, parseTableName(tableName), columns,
            filterColumn, filterValuesCount, excludeTombstoned,
            keysetField, keysetValuePresent);
    }

    /**
     * Phase 14.6 / A6 — schema-aware read-SQL builder. Renders the
     * ``FROM`` clause as ``"schema"."table"`` so external sources in
     * non-``public`` schemas can be read. Delegated to from the
     * legacy String-tableName overload via {@link #parseTableName}.
     */
    static String buildReadSql(
        String pattern, ParsedTableName parsed, List<String> columns,
        String filterColumn, int filterValuesCount,
        boolean excludeTombstoned,
        String keysetField, boolean keysetValuePresent
    ) {
        return buildReadSql(pattern, parsed, columns, filterColumn,
            filterValuesCount, excludeTombstoned, keysetField,
            keysetValuePresent, java.util.Collections.emptyList());
    }

    /**
     * ADR-077 — read-SQL builder with optional abstract-predicate pushdown.
     *
     * ``whereConds`` (may be empty) are AND-joined parameterized fragments
     * emitted AFTER the pattern's filter clause and BEFORE the tombstone
     * guard, so the bind order in executeParametricRead lines up:
     * keyset comparator → filter value(s) → where-condition value(s) →
     * LIMIT. Columns are validated against INFORMATION_SCHEMA upstream;
     * values are bound, never interpolated.
     */
    static String buildReadSql(
        String pattern, ParsedTableName parsed, List<String> columns,
        String filterColumn, int filterValuesCount,
        boolean excludeTombstoned,
        String keysetField, boolean keysetValuePresent,
        List<WhereCond> whereConds
    ) {
        boolean keysetMode = keysetField != null && !keysetField.isBlank();

        // Projection.
        String proj;
        if ("count".equals(pattern)) {
            proj = "COUNT(*) AS count";
        } else if (columns == null || columns.isEmpty()) {
            proj = "*";
        } else {
            StringBuilder pb = new StringBuilder();
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) pb.append(", ");
                pb.append('"').append(columns.get(i)).append('"');
            }
            // Keyset pagination reads the cursor (last_keyset_value) off the
            // keyset_field of the last returned row, so that column MUST be in
            // the projection — even when the caller didn't ask for it. Without
            // this, the row has no keyset_field, executeParametricRead surfaces
            // last_keyset_value=null despite has_more=true, and the dispatcher
            // cannot page forward. Append it once, only when absent.
            if (keysetMode && !columns.contains(keysetField)) {
                pb.append(", \"").append(keysetField).append('"');
            }
            proj = pb.toString();
        }

        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(proj);
        sql.append(" FROM ").append(renderQualifiedTable(parsed));

        // WHERE clauses (combined with AND).
        // Keyset comparator is emitted FIRST so its bind index is 1
        // (matching executeParametricRead's bind order).
        List<String> whereClauses = new ArrayList<>();
        if (keysetMode && keysetValuePresent) {
            whereClauses.add("\"" + keysetField + "\" > ?");
        }
        if ("by_id".equals(pattern)) {
            whereClauses.add("\"" + filterColumn + "\" = ?");
        } else if ("by_ids".equals(pattern)) {
            StringBuilder in = new StringBuilder();
            in.append('"').append(filterColumn).append("\" IN (");
            for (int i = 0; i < filterValuesCount; i++) {
                if (i > 0) in.append(", ");
                in.append('?');
            }
            in.append(')');
            whereClauses.add(in.toString());
        } else if (("list".equals(pattern) || "count".equals(pattern))
            && filterColumn != null && !filterColumn.isBlank()) {
            whereClauses.add("\"" + filterColumn + "\" = ?");
        }
        // ADR-077 — abstract-predicate pushdown fragments. Emitted after the
        // pattern filter, so bind order = keyset → filter → where-conds.
        if (whereConds != null) {
            for (WhereCond c : whereConds) {
                whereClauses.add(renderWhereFragment(c));
            }
        }
        if (excludeTombstoned) {
            whereClauses.add("\"tombstoned_at\" IS NULL");
        }

        if (!whereClauses.isEmpty()) {
            sql.append(" WHERE ");
            for (int i = 0; i < whereClauses.size(); i++) {
                if (i > 0) sql.append(" AND ");
                sql.append(whereClauses.get(i));
            }
        }

        // Phase 13 / REK.T2a — ORDER BY + LIMIT for keyset mode.
        if (keysetMode) {
            sql.append(" ORDER BY \"").append(keysetField)
               .append("\" ASC LIMIT ?");
        }

        return sql.toString();
    }

    // security(adr-096): execute(DataSource, String) — which ran an
    // arbitrary caller-supplied SQL string via a plain JDBC ``Statement``
    // (no bind parameters) — has been REMOVED. Combined with the now-gone
    // substituteParams, this was the unescaped raw-SQL execution surface.
    // The edge no longer executes any free-form SQL string. Every database
    // operation goes through executeParametric / executeParametricRead,
    // which validate table + column existence against INFORMATION_SCHEMA
    // and bind every value via PreparedStatement.
}
