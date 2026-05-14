package com.metafore.edge.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqlExecutorTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT * FROM users",
        "select count(*) from orders",
        "SHOW TABLES",
        "SHOW GLOBAL STATUS",
        "CREATE INDEX idx_name ON users(name)",
        "CREATE TABLE test (id INT)",
        "DELETE FROM users WHERE id = 1",
        "UPDATE users SET name = 'x' WHERE id = 1",
        "INSERT INTO users (name) VALUES ('x')"
    })
    void allowedSqlPasses(String sql) {
        assertTrue(SqlExecutor.isAllowed(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "DROP TABLE users",
        "ALTER TABLE users ADD col INT",
        "TRUNCATE users",
        "GRANT ALL ON *.* TO root",
        "CREATE DATABASE test",
        "CREATE VIEW v AS SELECT 1"
    })
    void disallowedSqlRejected(String sql) {
        assertFalse(SqlExecutor.isAllowed(sql));
    }

    @Test
    void nullAndBlankRejected() {
        assertFalse(SqlExecutor.isAllowed(null));
        assertFalse(SqlExecutor.isAllowed(""));
        assertFalse(SqlExecutor.isAllowed("   "));
    }

    @Test
    void parameterSubstitution() {
        String sql = "SELECT * FROM users WHERE id = ${id} AND name = '${name}'";
        Map<String, Object> params = Map.of("id", 42, "name", "alice");
        String result = SqlExecutor.substituteParams(sql, params);
        assertEquals("SELECT * FROM users WHERE id = 42 AND name = 'alice'", result);
    }

    @Test
    void parameterSubstitutionNoParams() {
        String sql = "SELECT 1";
        assertEquals(sql, SqlExecutor.substituteParams(sql, null));
        assertEquals(sql, SqlExecutor.substituteParams(sql, Map.of()));
    }

    @Test
    void parameterSubstitutionMissingParam() {
        String sql = "SELECT * FROM t WHERE id = ${id}";
        String result = SqlExecutor.substituteParams(sql, Map.of("other", "val"));
        assertEquals(sql, result);
    }

    // ── Slice 33.1.0 — parametric CRUD coverage ─────────────────────

    @Test
    void executeParametricRejectsUnknownOperation() {
        Map<String, Object> r = SqlExecutor.executeParametric(
            null, "upsert", "gtm_accounts",
            java.util.List.of("name"), java.util.List.of("Acme"),
            null, null
        );
        assertEquals("error", r.get("status"));
        assertTrue(((String) r.get("error")).contains("Unsupported operation"));
    }

    @Test
    void executeParametricRejectsBlankTableName() {
        Map<String, Object> r = SqlExecutor.executeParametric(
            null, "create", "",
            java.util.List.of("name"), java.util.List.of("Acme"),
            null, null
        );
        assertEquals("error", r.get("status"));
        assertTrue(((String) r.get("error")).contains("table_name"));
    }

    @Test
    void executeParametricRejectsMismatchedColumnsAndValues() {
        Map<String, Object> r = SqlExecutor.executeParametric(
            null, "create", "gtm_accounts",
            java.util.List.of("name", "stage"),
            java.util.List.of("Acme"),  // length 1 vs columns length 2
            null, null
        );
        assertEquals("error", r.get("status"));
        assertTrue(((String) r.get("error")).contains("equal length"));
    }

    @Test
    void executeParametricRejectsUpdateWithoutWhereColumn() {
        Map<String, Object> r = SqlExecutor.executeParametric(
            null, "update", "gtm_accounts",
            java.util.List.of("name"), java.util.List.of("Acme"),
            null, "rec-1"
        );
        assertEquals("error", r.get("status"));
        assertTrue(((String) r.get("error")).contains("where_column"));
    }

    @Test
    void validateValueTypesAcceptsSupportedRuntimeTypes() {
        java.util.List<Object> values = new java.util.ArrayList<>();
        values.add("text-value");                              // String → text/varchar/uuid
        values.add(42);                                        // Integer
        values.add(42L);                                       // Long
        values.add(3.14);                                      // Double
        values.add(new java.math.BigDecimal("99.95"));         // numeric
        values.add(true);                                      // Boolean
        values.add(new java.sql.Timestamp(0L));                // timestamp
        values.add(new java.sql.Date(0L));                     // date
        values.add(null);                                      // NULL
        assertNull(SqlExecutor.validateValueTypes(values, "rec-1"));
    }

    @Test
    void validateValueTypesRejectsListAndMap() {
        // JSONB / array shapes are explicitly out of 33.1.0 scope.
        // Edge fails clearly with "Unsupported column type" + the
        // list of types the slice does support.
        java.util.List<Object> values = new java.util.ArrayList<>();
        values.add(java.util.List.of("a", "b"));  // would be array column
        String err = SqlExecutor.validateValueTypes(values, null);
        assertNotNull(err);
        assertTrue(err.contains("Unsupported column type"));
        assertTrue(err.contains("values[0]"));

        java.util.List<Object> values2 = new java.util.ArrayList<>();
        values2.add("ok");
        values2.add(java.util.Map.of("k", "v"));  // would be JSONB column
        String err2 = SqlExecutor.validateValueTypes(values2, null);
        assertNotNull(err2);
        assertTrue(err2.contains("values[1]"));
    }

    @Test
    void validateValueTypesRejectsByteArray() {
        // bytea isn't in the supported set for 33.1.0 either.
        java.util.List<Object> values = java.util.List.of(new byte[]{1, 2, 3});
        String err = SqlExecutor.validateValueTypes(values, null);
        assertNotNull(err);
        assertTrue(err.contains("Unsupported column type"));
    }

    @Test
    void validateValueTypesAlsoChecksWhereValue() {
        java.util.List<Object> okValues = java.util.List.of("name");
        // where_value of an unsupported type fails with the
        // distinct ``where_value`` label.
        Object badWhere = java.util.List.of("a");
        String err = SqlExecutor.validateValueTypes(okValues, badWhere);
        assertNotNull(err);
        assertTrue(err.contains("where_value"));
    }

    // ── Slice 33.1.0b — select operation ────────────────────────────

    @Test
    void executeParametricRejectsUnknownOperationListsAllFour() {
        // Sanity — error message should now mention all four supported
        // operations (Slice 33.1.0b adds ``select``).
        Map<String, Object> r = SqlExecutor.executeParametric(
            null, "upsert", "gtm_accounts",
            java.util.List.of("name"), java.util.List.of("Acme"),
            null, null
        );
        assertEquals("error", r.get("status"));
        assertTrue(((String) r.get("error")).contains("create/update/delete/select"));
    }

    @Test
    void executeParametricSelectRejectsBlankWhereColumn() {
        Map<String, Object> r = SqlExecutor.executeParametric(
            null, "select", "gtm_accounts",
            java.util.List.of("name", "industry"),
            java.util.Collections.emptyList(),
            null, "rec-1"
        );
        assertEquals("error", r.get("status"));
        assertTrue(((String) r.get("error")).contains("where_column"));
    }

    @Test
    void validateValueTypesRejectsListAsWhereValueForSelect() {
        // ``select`` only binds where_value; ``values`` is empty.
        // An unsupported where_value type must fail distinctly with
        // the ``where_value`` label. validateValueTypes is the gate
        // executeParametric calls before any DB contact.
        String err = SqlExecutor.validateValueTypes(
            java.util.Collections.emptyList(),
            java.util.List.of("a", "b")  // unsupported runtime type
        );
        assertNotNull(err);
        assertTrue(err.contains("Unsupported column type"));
        assertTrue(err.contains("where_value"));
    }

    @Test
    void validateValueTypesAcceptsEmptyValuesAndOkWhereValue() {
        // Sanity: empty values + a string where_value passes — proves
        // the loop doesn't trip on a 0-length list and the where_value
        // path runs independently. (executeParametric for select goes
        // through this exact shape.)
        assertNull(SqlExecutor.validateValueTypes(
            java.util.Collections.emptyList(),
            "rec-1"
        ));
    }

    // ── Slice 33.1.0c — column-type-aware bindValue ─────────────────

    /**
     * Hand-rolled BindCapture — Java reflection Proxy that records every
     * method call on a PreparedStatement. Avoids adding Mockito to the
     * edge dep tree for one slice. Mockito can land alongside the
     * deferred Testcontainers slice when integration test infra wants
     * broader mocking.
     *
     * Returns null from invoke() — fine for void setXxx methods + any
     * Object-returning method we don't call in these tests. Primitive-
     * returning methods (getInt, etc.) would NPE; we don't call them.
     */
    static final class BindCapture
        implements java.lang.reflect.InvocationHandler {
        static final class Call {
            final String method;
            final Object[] args;
            Call(String m, Object[] a) { this.method = m; this.args = a; }
        }
        final java.util.List<Call> calls = new java.util.ArrayList<>();
        public Object invoke(Object proxy, java.lang.reflect.Method m,
                             Object[] args) {
            calls.add(new Call(m.getName(), args));
            return null;
        }
        java.sql.PreparedStatement ps() {
            return (java.sql.PreparedStatement)
                java.lang.reflect.Proxy.newProxyInstance(
                    java.sql.PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{java.sql.PreparedStatement.class},
                    this
                );
        }
    }

    @Test
    void bindValueRoutesTimestamptzStringToSetTimestamp() throws Exception {
        BindCapture cap = new BindCapture();
        String iso = "2026-04-29T12:00:00.123456+00:00";
        SqlExecutor.bindValue(cap.ps(), 1, iso, "timestamp with time zone");
        assertEquals(1, cap.calls.size());
        BindCapture.Call c = cap.calls.get(0);
        assertEquals("setTimestamp", c.method);
        assertEquals(Integer.valueOf(1), c.args[0]);
        assertTrue(c.args[1] instanceof java.sql.Timestamp);
        java.time.Instant expected = java.time.OffsetDateTime
            .parse(iso).toInstant();
        assertEquals(expected, ((java.sql.Timestamp) c.args[1]).toInstant());
    }

    @Test
    void bindValueRoutesDateStringToSetDate() throws Exception {
        BindCapture cap = new BindCapture();
        SqlExecutor.bindValue(cap.ps(), 2, "2026-04-29", "date");
        assertEquals(1, cap.calls.size());
        BindCapture.Call c = cap.calls.get(0);
        assertEquals("setDate", c.method);
        assertEquals(Integer.valueOf(2), c.args[0]);
        assertTrue(c.args[1] instanceof java.sql.Date);
        assertEquals(
            java.sql.Date.valueOf(java.time.LocalDate.parse("2026-04-29")),
            c.args[1]
        );
    }

    @Test
    void bindValueRoutesNaiveTimestampStringToSetTimestamp() throws Exception {
        // ``timestamp without time zone`` — naive ISO with no offset.
        BindCapture cap = new BindCapture();
        SqlExecutor.bindValue(
            cap.ps(), 3, "2026-04-29T12:00:00.123456",
            "timestamp without time zone"
        );
        assertEquals(1, cap.calls.size());
        BindCapture.Call c = cap.calls.get(0);
        assertEquals("setTimestamp", c.method);
        assertEquals(
            java.sql.Timestamp.valueOf(
                java.time.LocalDateTime.parse("2026-04-29T12:00:00.123456")),
            c.args[1]
        );
    }

    @Test
    void bindValueKeepsExistingPathForVarcharColumn() throws Exception {
        // Regression: non-timestamp column + String value MUST still
        // hit setString (legacy path). ISO-like strings landing on a
        // text column don't get reinterpreted.
        BindCapture cap = new BindCapture();
        SqlExecutor.bindValue(
            cap.ps(), 4, "Phluence", "character varying"
        );
        assertEquals(1, cap.calls.size());
        BindCapture.Call c = cap.calls.get(0);
        assertEquals("setString", c.method);
        assertEquals("Phluence", c.args[1]);
    }

    @Test
    void bindValueHandlesNullForTimestampColumn() throws Exception {
        // Null on a timestamp column must NOT attempt ISO parse —
        // falls through to legacy setObject(null) for clean NULL bind.
        BindCapture cap = new BindCapture();
        SqlExecutor.bindValue(
            cap.ps(), 5, null, "timestamp with time zone"
        );
        assertEquals(1, cap.calls.size());
        BindCapture.Call c = cap.calls.get(0);
        assertEquals("setObject", c.method);
        assertNull(c.args[1]);
    }

    @Test
    void bindValueAcceptsTypedTimestampOnTimestampColumn() throws Exception {
        // When the value is already a typed java.sql.Timestamp, the
        // ISO-parse branch must NOT trigger (CharSequence check fails).
        // Falls through to legacy path which binds via setTimestamp.
        BindCapture cap = new BindCapture();
        java.sql.Timestamp ts = java.sql.Timestamp.from(
            java.time.Instant.parse("2026-04-29T12:00:00.123456Z"));
        SqlExecutor.bindValue(
            cap.ps(), 6, ts, "timestamp with time zone"
        );
        assertEquals(1, cap.calls.size());
        BindCapture.Call c = cap.calls.get(0);
        assertEquals("setTimestamp", c.method);
        assertSame(ts, c.args[1]);
    }

    @Test
    void bindValueFallsThroughWhenColumnTypeUnknown() throws Exception {
        // Backwards compat: null columnType keeps the legacy path
        // (existing 33.1.0/0a/0b callers that don't pass type).
        BindCapture cap = new BindCapture();
        SqlExecutor.bindValue(cap.ps(), 7, "Phluence", null);
        assertEquals(1, cap.calls.size());
        assertEquals("setString", cap.calls.get(0).method);
    }

    @Test
    void bindValueRaisesOnInvalidTimestamptzString() throws Exception {
        // Defensive: bad ISO on a timestamptz column surfaces as a
        // SQLException naming the offending value + column type.
        BindCapture cap = new BindCapture();
        java.sql.SQLException exc = assertThrows(
            java.sql.SQLException.class, () ->
                SqlExecutor.bindValue(cap.ps(), 1,
                    "not-an-iso-timestamp", "timestamp with time zone")
        );
        assertTrue(exc.getMessage().contains("timestamptz"));
        assertTrue(exc.getMessage().contains("not-an-iso-timestamp"));
    }

    // ── ADR-016 F1.T2b — read patterns (by_id / by_ids / list / count) ──
    //
    // The four pattern variants are testable at two layers:
    //   - buildReadSql: pure string-building, no DB. Exercises the
    //     SQL template for each pattern + filter combination.
    //   - executeParametricRead: with a null DataSource, the validation
    //     gates surface error envelopes deterministically without
    //     reaching the JDBC layer (mirrors the existing
    //     executeParametric validation-only tests).

    @Test
    void buildReadSqlByIdSelectsAllColumnsByDefault() {
        String sql = SqlExecutor.buildReadSql(
            "by_id", "patients", java.util.Collections.emptyList(),
            "record_id", 0, false
        );
        assertEquals(
            "SELECT * FROM \"patients\" WHERE \"record_id\" = ?",
            sql
        );
    }

    @Test
    void buildReadSqlByIdRespectsProjection() {
        String sql = SqlExecutor.buildReadSql(
            "by_id", "patients",
            java.util.List.of("record_id", "name", "consent_status"),
            "record_id", 0, false
        );
        assertEquals(
            "SELECT \"record_id\", \"name\", \"consent_status\" "
            + "FROM \"patients\" WHERE \"record_id\" = ?",
            sql
        );
    }

    @Test
    void buildReadSqlByIdsRendersInClauseWithCorrectPlaceholderCount() {
        String sql = SqlExecutor.buildReadSql(
            "by_ids", "providers",
            java.util.List.of("record_id", "name"),
            "record_id", 3, false
        );
        assertEquals(
            "SELECT \"record_id\", \"name\" FROM \"providers\" "
            + "WHERE \"record_id\" IN (?, ?, ?)",
            sql
        );
    }

    @Test
    void buildReadSqlByIdsSinglePlaceholderForListOfOne() {
        // Edge case: filter_values of length 1 should still render IN
        // (?), not = ?. The dispatch-side pattern choice (by_id vs
        // by_ids) is the caller's decision.
        String sql = SqlExecutor.buildReadSql(
            "by_ids", "t", java.util.Collections.emptyList(),
            "fc", 1, false
        );
        assertEquals(
            "SELECT * FROM \"t\" WHERE \"fc\" IN (?)",
            sql
        );
    }

    @Test
    void buildReadSqlListNoFiltersIsBareSelect() {
        String sql = SqlExecutor.buildReadSql(
            "list", "patients", java.util.Collections.emptyList(),
            null, 0, false
        );
        assertEquals("SELECT * FROM \"patients\"", sql);
    }

    @Test
    void buildReadSqlListExcludeTombstonedOnly() {
        String sql = SqlExecutor.buildReadSql(
            "list", "patients", java.util.List.of("record_id", "name"),
            null, 0, true
        );
        assertEquals(
            "SELECT \"record_id\", \"name\" FROM \"patients\" "
            + "WHERE \"tombstoned_at\" IS NULL",
            sql
        );
    }

    @Test
    void buildReadSqlListFilterColumnOnly() {
        // "interactions for Sarah Chen the provider" — list of
        // interactions filtered by provider_id.
        String sql = SqlExecutor.buildReadSql(
            "list", "interactions", java.util.List.of("record_id", "summary"),
            "provider_id", 0, false
        );
        assertEquals(
            "SELECT \"record_id\", \"summary\" FROM \"interactions\" "
            + "WHERE \"provider_id\" = ?",
            sql
        );
    }

    @Test
    void buildReadSqlListFilterColumnAndExcludeTombstonedCombineWithAnd() {
        String sql = SqlExecutor.buildReadSql(
            "list", "interactions",
            java.util.List.of("record_id"),
            "provider_id", 0, true
        );
        assertEquals(
            "SELECT \"record_id\" FROM \"interactions\" "
            + "WHERE \"provider_id\" = ? "
            + "AND \"tombstoned_at\" IS NULL",
            sql
        );
    }

    @Test
    void buildReadSqlCountIgnoresProjection() {
        // count pattern always emits COUNT(*) regardless of caller-
        // supplied columns.
        String sql = SqlExecutor.buildReadSql(
            "count", "patients",
            java.util.List.of("record_id", "name"),  // ignored
            null, 0, false
        );
        assertEquals(
            "SELECT COUNT(*) AS count FROM \"patients\"",
            sql
        );
    }

    @Test
    void buildReadSqlCountWithFilterAndTombstone() {
        // auto_seq's COUNT(*) for next-id resolution + tombstone-aware
        // count for "how many active Patients for this Provider".
        String sql = SqlExecutor.buildReadSql(
            "count", "interactions", java.util.Collections.emptyList(),
            "provider_id", 0, true
        );
        assertEquals(
            "SELECT COUNT(*) AS count FROM \"interactions\" "
            + "WHERE \"provider_id\" = ? "
            + "AND \"tombstoned_at\" IS NULL",
            sql
        );
    }

    @Test
    void executeParametricReadRejectsUnknownPattern() {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("pattern", "by_record_id");  // typo
        payload.put("table_name", "patients");
        Map<String, Object> r = SqlExecutor.executeParametricRead(
            null, payload);
        assertEquals("error", r.get("status"));
        assertTrue(((String) r.get("error")).contains("by_record_id"));
        assertTrue(((String) r.get("error")).contains(
            "by_id/by_ids/list/count"));
    }

    @Test
    void executeParametricReadRejectsBlankTableName() {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("pattern", "by_id");
        payload.put("table_name", "");
        Map<String, Object> r = SqlExecutor.executeParametricRead(
            null, payload);
        assertEquals("error", r.get("status"));
        assertTrue(((String) r.get("error")).contains("table_name"));
    }

    @Test
    void executeParametricReadRejectsByIdWithoutFilterColumn() {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("pattern", "by_id");
        payload.put("table_name", "patients");
        payload.put("filter_value", "rec-1");
        Map<String, Object> r = SqlExecutor.executeParametricRead(
            null, payload);
        assertEquals("error", r.get("status"));
        assertTrue(((String) r.get("error")).contains("filter_column"));
    }

    @Test
    void executeParametricReadRejectsByIdWithoutFilterValue() {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("pattern", "by_id");
        payload.put("table_name", "patients");
        payload.put("filter_column", "record_id");
        Map<String, Object> r = SqlExecutor.executeParametricRead(
            null, payload);
        assertEquals("error", r.get("status"));
        assertTrue(((String) r.get("error")).contains("filter_value"));
    }

    @Test
    void executeParametricReadRejectsByIdsWithoutFilterValues() {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("pattern", "by_ids");
        payload.put("table_name", "patients");
        payload.put("filter_column", "record_id");
        Map<String, Object> r = SqlExecutor.executeParametricRead(
            null, payload);
        assertEquals("error", r.get("status"));
        assertTrue(((String) r.get("error")).contains("filter_values"));
    }

    @Test
    void executeParametricReadRejectsByIdsWithEmptyList() {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("pattern", "by_ids");
        payload.put("table_name", "patients");
        payload.put("filter_column", "record_id");
        payload.put("filter_values", java.util.Collections.emptyList());
        Map<String, Object> r = SqlExecutor.executeParametricRead(
            null, payload);
        assertEquals("error", r.get("status"));
        assertTrue(((String) r.get("error")).contains("non-empty"));
    }

    @Test
    void executeParametricReadRejectsByIdsOversizedList() {
        // Defensive: cap IN list at MAX_ROWS to keep PreparedStatement
        // round-trip predictable.
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("pattern", "by_ids");
        payload.put("table_name", "patients");
        payload.put("filter_column", "record_id");
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 101; i++) ids.add("rec-" + i);
        payload.put("filter_values", ids);
        Map<String, Object> r = SqlExecutor.executeParametricRead(
            null, payload);
        assertEquals("error", r.get("status"));
        assertTrue(((String) r.get("error")).contains("MAX_ROWS"));
    }

    @Test
    void executeParametricReadRejectsListFilterColumnWithoutValue() {
        // list/count: filter_column is optional; if provided,
        // filter_value is required.
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("pattern", "list");
        payload.put("table_name", "interactions");
        payload.put("filter_column", "provider_id");
        Map<String, Object> r = SqlExecutor.executeParametricRead(
            null, payload);
        assertEquals("error", r.get("status"));
        assertTrue(((String) r.get("error")).contains("filter_value"));
    }

    @Test
    void executeParametricReadRejectsCountFilterColumnWithoutValue() {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("pattern", "count");
        payload.put("table_name", "interactions");
        payload.put("filter_column", "provider_id");
        Map<String, Object> r = SqlExecutor.executeParametricRead(
            null, payload);
        assertEquals("error", r.get("status"));
        assertTrue(((String) r.get("error")).contains("filter_value"));
    }

    @Test
    void executeParametricReadAcceptsListWithNoFilterAndNoTombstoneExclusion() {
        // Valid: list pattern with neither filter_column nor
        // exclude_tombstoned → full-table SELECT. Validation passes;
        // caller's tradeoff to manage row volume vs MAX_ROWS cap.
        // (DB-touching execution path can't run without a DataSource,
        // but the validation gates should clear.)
        // We verify validation by reaching the JDBC connection step:
        // a null DataSource produces a NPE-wrapped SQLException AFTER
        // validation. The error message will mention DataSource, not
        // any validation field — proving validation passed.
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("pattern", "list");
        payload.put("table_name", "patients");
        // No filter_column, no filter_value, no exclude_tombstoned.
        // Validation should clear and the call should attempt a DB
        // connection (failing with a clear error on null ds).
        assertThrows(NullPointerException.class,
            () -> SqlExecutor.executeParametricRead(null, payload));
    }

    // ── Phase 13 / REK.T2a — keyset pagination ──────────────────────
    //
    // Acceptance gate cases from the brief:
    //   (a) keyset_field=null path identical to today (back-compat)
    //   (b) keyset_field set with filter
    //   (c) keyset_field set without filter
    //   (d) IN-clause + keyset combination — rejected (keyset is
    //       list-only; by_ids+keyset is a contradiction)
    //   (e) tombstoned-exclude + keyset
    //   (f) has_more=true when rows.size > limit (covered via SQL
    //       emission asserting LIMIT ? + bind discipline in
    //       executeParametricRead validation tests; full
    //       row-trimming verified at smoke-test layer)
    //   (g) limit clamped to MAX_ROWS-1 when caller asks for more
    //
    // SQL emission is the primary test surface (deterministic, no DB).
    // executeParametricRead validation gates are tested with null
    // DataSource to confirm validation rejects bad payloads BEFORE
    // any DB contact, matching the existing test idiom.

    @Test
    void buildReadSqlLegacyOverloadIdenticalToPrevious() {
        // Back-compat invariant — the 6-arg overload (pre-REK.T2a
        // signature) MUST produce byte-identical SQL to today's
        // emission for every pattern. Spot-check the four pattern
        // variants the F1.T2b suite asserts.
        assertEquals(
            "SELECT * FROM \"patients\" WHERE \"record_id\" = ?",
            SqlExecutor.buildReadSql(
                "by_id", "patients", java.util.Collections.emptyList(),
                "record_id", 0, false)
        );
        assertEquals(
            "SELECT \"record_id\", \"name\" FROM \"providers\" "
            + "WHERE \"record_id\" IN (?, ?, ?)",
            SqlExecutor.buildReadSql(
                "by_ids", "providers",
                java.util.List.of("record_id", "name"),
                "record_id", 3, false)
        );
        assertEquals(
            "SELECT \"record_id\" FROM \"interactions\" "
            + "WHERE \"provider_id\" = ? "
            + "AND \"tombstoned_at\" IS NULL",
            SqlExecutor.buildReadSql(
                "list", "interactions",
                java.util.List.of("record_id"),
                "provider_id", 0, true)
        );
        assertEquals(
            "SELECT COUNT(*) AS count FROM \"patients\"",
            SqlExecutor.buildReadSql(
                "count", "patients",
                java.util.List.of("record_id", "name"),
                null, 0, false)
        );
    }

    @Test
    void buildReadSqlKeysetNullBehavesAsLegacy() {
        // Acceptance case (a) — passing keysetField=null to the 8-arg
        // overload MUST be byte-identical to the 6-arg overload's
        // output. This is the deployed-dispatcher safety net.
        String legacy = SqlExecutor.buildReadSql(
            "list", "patients", java.util.List.of("record_id", "name"),
            "provider_id", 0, true
        );
        String withNullKeyset = SqlExecutor.buildReadSql(
            "list", "patients", java.util.List.of("record_id", "name"),
            "provider_id", 0, true, null, false
        );
        assertEquals(legacy, withNullKeyset);
        // Sanity: no ORDER BY, no LIMIT leaked into the legacy path.
        assertFalse(withNullKeyset.contains("ORDER BY"));
        assertFalse(withNullKeyset.contains("LIMIT"));
    }

    @Test
    void buildReadSqlKeysetFirstPageNoCursorNoFilter() {
        // Acceptance case (c) — first-page keyset fetch with no filter,
        // no tombstone exclusion, no cursor pivot. Caller passes
        // keysetValuePresent=false (no WHERE keyset > ? clause) but
        // ORDER BY + LIMIT still emit.
        String sql = SqlExecutor.buildReadSql(
            "list", "patients",
            java.util.List.of("record_id", "name"),
            null, 0, false,
            "record_id", false
        );
        assertEquals(
            "SELECT \"record_id\", \"name\" FROM \"patients\" "
            + "ORDER BY \"record_id\" ASC LIMIT ?",
            sql
        );
    }

    @Test
    void buildReadSqlKeysetWithCursorNoFilter() {
        // Acceptance case (c) — second-page fetch: keyset comparator
        // is the only WHERE predicate, plus ORDER BY + LIMIT.
        String sql = SqlExecutor.buildReadSql(
            "list", "patients",
            java.util.List.of("record_id", "name"),
            null, 0, false,
            "record_id", true
        );
        assertEquals(
            "SELECT \"record_id\", \"name\" FROM \"patients\" "
            + "WHERE \"record_id\" > ? "
            + "ORDER BY \"record_id\" ASC LIMIT ?",
            sql
        );
    }

    @Test
    void buildReadSqlKeysetWithCursorAndFilterColumn() {
        // Acceptance case (b) — keyset cursor combined with a filter
        // column. Keyset comparator emits FIRST so its bind index is
        // 1, then filter, then LIMIT. Critical: bind order in
        // executeParametricRead must match this emission order.
        String sql = SqlExecutor.buildReadSql(
            "list", "interactions",
            java.util.List.of("record_id", "summary"),
            "provider_id", 0, false,
            "record_id", true
        );
        assertEquals(
            "SELECT \"record_id\", \"summary\" FROM \"interactions\" "
            + "WHERE \"record_id\" > ? "
            + "AND \"provider_id\" = ? "
            + "ORDER BY \"record_id\" ASC LIMIT ?",
            sql
        );
    }

    @Test
    void buildReadSqlKeysetWithExcludeTombstoned() {
        // Acceptance case (e) — keyset + tombstone exclusion. Order:
        // keyset comparator, then tombstone predicate. ORDER BY +
        // LIMIT trail.
        String sql = SqlExecutor.buildReadSql(
            "list", "patients",
            java.util.List.of("record_id", "name"),
            null, 0, true,
            "record_id", true
        );
        assertEquals(
            "SELECT \"record_id\", \"name\" FROM \"patients\" "
            + "WHERE \"record_id\" > ? "
            + "AND \"tombstoned_at\" IS NULL "
            + "ORDER BY \"record_id\" ASC LIMIT ?",
            sql
        );
    }

    @Test
    void buildReadSqlKeysetWithFilterAndTombstoneCombined() {
        // Full combination: keyset cursor + filter column + tombstone
        // exclusion. Bind order: keyset_value, filter_value, limit+1.
        String sql = SqlExecutor.buildReadSql(
            "list", "interactions",
            java.util.List.of("record_id", "summary"),
            "provider_id", 0, true,
            "record_id", true
        );
        assertEquals(
            "SELECT \"record_id\", \"summary\" FROM \"interactions\" "
            + "WHERE \"record_id\" > ? "
            + "AND \"provider_id\" = ? "
            + "AND \"tombstoned_at\" IS NULL "
            + "ORDER BY \"record_id\" ASC LIMIT ?",
            sql
        );
    }

    @Test
    void executeParametricReadRejectsKeysetWithByIdPattern() {
        // Acceptance case (d) — keyset semantics are list-only.
        // by_id + keyset is a contradiction (by_id returns ≤1 row,
        // ORDER BY + LIMIT add no value). Reject loudly at edge.
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("pattern", "by_id");
        payload.put("table_name", "patients");
        payload.put("filter_column", "record_id");
        payload.put("filter_value", "rec-1");
        payload.put("keyset_field", "record_id");
        payload.put("limit", 50);
        Map<String, Object> r = SqlExecutor.executeParametricRead(
            null, payload);
        assertEquals("error", r.get("status"));
        assertTrue(((String) r.get("error")).contains(
            "keyset_field only supported with pattern=list"));
        assertTrue(((String) r.get("error")).contains("pattern=by_id"));
    }

    @Test
    void executeParametricReadRejectsKeysetWithByIdsPattern() {
        // Acceptance case (d, continued) — by_ids + keyset rejected
        // for the same reason.
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("pattern", "by_ids");
        payload.put("table_name", "patients");
        payload.put("filter_column", "record_id");
        payload.put("filter_values", java.util.List.of("rec-1", "rec-2"));
        payload.put("keyset_field", "record_id");
        payload.put("limit", 50);
        Map<String, Object> r = SqlExecutor.executeParametricRead(
            null, payload);
        assertEquals("error", r.get("status"));
        assertTrue(((String) r.get("error")).contains(
            "keyset_field only supported with pattern=list"));
    }

    @Test
    void executeParametricReadRejectsKeysetWithCountPattern() {
        // keyset + count is also rejected — ORDER BY + LIMIT change
        // scalar count semantics.
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("pattern", "count");
        payload.put("table_name", "patients");
        payload.put("keyset_field", "record_id");
        payload.put("limit", 50);
        Map<String, Object> r = SqlExecutor.executeParametricRead(
            null, payload);
        assertEquals("error", r.get("status"));
        assertTrue(((String) r.get("error")).contains(
            "keyset_field only supported with pattern=list"));
    }

    @Test
    void executeParametricReadRejectsKeysetWithoutLimit() {
        // limit is required when keyset_field is set. Edge cannot
        // emit a bounded LIMIT ? without a value.
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("pattern", "list");
        payload.put("table_name", "patients");
        payload.put("keyset_field", "record_id");
        // No limit.
        Map<String, Object> r = SqlExecutor.executeParametricRead(
            null, payload);
        assertEquals("error", r.get("status"));
        assertTrue(((String) r.get("error")).contains(
            "keyset_field requires limit"));
    }

    @Test
    void executeParametricReadRejectsKeysetWithNonNumericLimit() {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("pattern", "list");
        payload.put("table_name", "patients");
        payload.put("keyset_field", "record_id");
        payload.put("limit", "50");  // string, not Number
        Map<String, Object> r = SqlExecutor.executeParametricRead(
            null, payload);
        assertEquals("error", r.get("status"));
        assertTrue(((String) r.get("error")).contains(
            "limit must be a number"));
    }

    @Test
    void executeParametricReadRejectsKeysetWithZeroLimit() {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("pattern", "list");
        payload.put("table_name", "patients");
        payload.put("keyset_field", "record_id");
        payload.put("limit", 0);
        Map<String, Object> r = SqlExecutor.executeParametricRead(
            null, payload);
        assertEquals("error", r.get("status"));
        assertTrue(((String) r.get("error")).contains(
            "limit must be positive"));
    }

    @Test
    void executeParametricReadRejectsKeysetWithNegativeLimit() {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("pattern", "list");
        payload.put("table_name", "patients");
        payload.put("keyset_field", "record_id");
        payload.put("limit", -5);
        Map<String, Object> r = SqlExecutor.executeParametricRead(
            null, payload);
        assertEquals("error", r.get("status"));
        assertTrue(((String) r.get("error")).contains(
            "limit must be positive"));
    }

    @Test
    void executeParametricReadAcceptsKeysetWithValidLimit() {
        // Acceptance case (f, validation half) — valid keyset payload
        // must clear all validation and reach the JDBC connection
        // step. Null DataSource surfaces NPE post-validation —
        // proving every validation gate passed.
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("pattern", "list");
        payload.put("table_name", "patients");
        payload.put("keyset_field", "record_id");
        payload.put("limit", 50);
        assertThrows(NullPointerException.class,
            () -> SqlExecutor.executeParametricRead(null, payload));
    }

    @Test
    void executeParametricReadAcceptsKeysetWithCursorAndFilter() {
        // Validation half of acceptance case (b) — keyset + filter +
        // cursor value clears all gates.
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("pattern", "list");
        payload.put("table_name", "interactions");
        payload.put("filter_column", "provider_id");
        payload.put("filter_value", "prov-1");
        payload.put("keyset_field", "record_id");
        payload.put("keyset_value", "rec-cursor-50");
        payload.put("limit", 50);
        payload.put("exclude_tombstoned", true);
        assertThrows(NullPointerException.class,
            () -> SqlExecutor.executeParametricRead(null, payload));
    }

    @Test
    void validateValueTypesRejectsListAsKeysetValue() {
        // Type-supported gate covers keyset_value via the same
        // validateValueTypes call as where_value (executeParametricRead
        // routes keyset_value through validateValueTypes with the
        // where_value slot). The static check is identical, so
        // exercising the gate directly mirrors the existing
        // validateValueTypes tests above.
        Object badKeysetValue = java.util.List.of("a", "b");
        String err = SqlExecutor.validateValueTypes(
            java.util.Collections.emptyList(), badKeysetValue);
        assertNotNull(err);
        assertTrue(err.contains("Unsupported column type"));
        // executeParametricRead surfaces this via the where_value
        // label — fine, since the label is for diagnostic purposes
        // and the gate itself is identical.
        assertTrue(err.contains("where_value"));
    }

    @Test
    void executeParametricReadKeysetBlankFieldIsLegacyMode() {
        // keyset_field is blank → treated as absent (back-compat with
        // dispatchers that emit blank strings rather than null).
        // Falls through to legacy list semantics. Without DataSource,
        // the call reaches DB step (proving validation cleared, not
        // taking the keyset branch).
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("pattern", "list");
        payload.put("table_name", "patients");
        payload.put("keyset_field", "");
        // No limit — would fail validation IF keyset mode were active.
        assertThrows(NullPointerException.class,
            () -> SqlExecutor.executeParametricRead(null, payload));
    }

    @Test
    void buildReadSqlKeysetClampedLimitProducesCorrectSql() {
        // Acceptance case (g) — clamping happens in
        // executeParametricRead BEFORE SQL build, so buildReadSql
        // itself only ever sees the clamped (already-safe) limit.
        // The SQL emission contains the parameterized LIMIT ?, not a
        // string-interpolated number. Bind discipline check.
        String sql = SqlExecutor.buildReadSql(
            "list", "patients",
            java.util.List.of("record_id", "name"),
            null, 0, false,
            "record_id", true
        );
        assertTrue(sql.contains("LIMIT ?"),
            "LIMIT value must bind via PreparedStatement parameter, "
            + "not string-interpolate; got: " + sql);
        // The keyset comparator binds via ? too — no value
        // interpolation anywhere.
        assertTrue(sql.contains("\"record_id\" > ?"),
            "keyset comparator must bind via parameter; got: " + sql);
        // Identifier IS interpolated (quoted) — that's the contract
        // per quoteIdentifier discipline.
        assertTrue(sql.contains("\"record_id\""),
            "keyset_field interpolates as quoted identifier; got: " + sql);
    }
}
