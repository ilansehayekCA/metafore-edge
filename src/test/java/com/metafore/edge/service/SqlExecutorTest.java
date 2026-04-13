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
}
