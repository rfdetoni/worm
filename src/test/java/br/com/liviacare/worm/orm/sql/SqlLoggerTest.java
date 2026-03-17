package br.com.liviacare.worm.orm.sql;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SqlLoggerTest {

    @Test
    void format_noParams_returnsSqlAsIs() {
        assertEquals("SELECT * FROM users", SqlLogger.format("SELECT * FROM users", List.of()));
    }

    @Test
    void format_withNullParams_returnsSqlAsIs() {
        assertEquals("SELECT 1", SqlLogger.format("SELECT 1", null));
    }

    @Test
    void format_stringParam_isQuoted() {
        assertEquals("WHERE name = 'Alice'", SqlLogger.format("WHERE name = ?", List.of("Alice")));
    }

    @Test
    void format_numberParam_isUnquoted() {
        assertEquals("WHERE age = 42", SqlLogger.format("WHERE age = ?", List.of(42)));
    }

    @Test
    void format_booleanParam_isUnquoted() {
        assertEquals("WHERE active = true", SqlLogger.format("WHERE active = ?", List.of(true)));
    }

    @Test
    void format_nullParam_rendersNULL() {
        List<Object> params = new ArrayList<>();
        params.add(null);
        assertEquals("WHERE col = NULL", SqlLogger.format("WHERE col = ?", params));
    }

    @Test
    void format_uuidParam_isQuoted() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String result = SqlLogger.format("WHERE id = ?", List.of(id));
        assertEquals("WHERE id = '00000000-0000-0000-0000-000000000001'", result);
    }

    @Test
    void format_multipleParams_replacedInOrder() {
        assertEquals("INSERT INTO t(a,b) VALUES('foo',99)",
                SqlLogger.format("INSERT INTO t(a,b) VALUES(?,?)", List.of("foo", 99)));
    }

    @Test
    void format_sqlWithNewlines_collapsedToSpaces() {
        String sql = "SELECT *\nFROM users\nWHERE id = ?";
        String result = SqlLogger.format(sql, List.of(1));
        assertFalse(result.contains("\n"));
        assertTrue(result.contains("WHERE id = 1"));
    }

    @Test
    void format_morePlaceholdersThanParams_remainAsIs() {
        assertEquals("WHERE a = 'x' AND b = ?",
                SqlLogger.format("WHERE a = ? AND b = ?", List.of("x")));
    }
}
