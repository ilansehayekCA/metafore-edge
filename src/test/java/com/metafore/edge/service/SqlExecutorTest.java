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
}
