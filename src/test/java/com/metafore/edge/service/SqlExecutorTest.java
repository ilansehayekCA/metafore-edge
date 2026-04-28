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
}
