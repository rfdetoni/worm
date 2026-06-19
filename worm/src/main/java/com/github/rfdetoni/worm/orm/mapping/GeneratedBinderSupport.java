package com.github.rfdetoni.worm.orm.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Shared conversion helpers for APT-generated entity binders.
 */
public final class GeneratedBinderSupport {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private GeneratedBinderSupport() {
    }

    public static Object prepare(Object value, boolean json, boolean enumType) {
        if (value == null) {
            return null;
        }
        Object normalized = enumType ? ((Enum<?>) value).name() : normalizeJdbcValue(value);
        if (!json) {
            return normalized;
        }
        return toJsonb(normalized);
    }

    public static Object normalizeJdbcValue(Object value) {
        if (value instanceof Instant instant) {
            return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
        }
        return value;
    }

    public static Object[] normalizeJdbcValues(Object[] values) {
        Object[] normalized = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            normalized[i] = normalizeJdbcValue(values[i]);
        }
        return normalized;
    }

    public static JdbcClient.StatementSpec bindPositional(JdbcClient.StatementSpec spec, int index, Object value) {
        Object normalized = normalizeJdbcValue(value);
        Integer sqlType = jdbcSqlType(value);
        if (sqlType != null) {
            return spec.param(index, normalized, sqlType);
        }
        return spec.param(index, normalized);
    }

    public static void bindPreparedStatement(PreparedStatement ps, int index, Object value) throws SQLException {
        Object normalized = normalizeJdbcValue(value);
        Integer sqlType = jdbcSqlType(value);
        if (sqlType != null) {
            ps.setObject(index, normalized, sqlType);
            return;
        }
        ps.setObject(index, normalized);
    }

    private static Integer jdbcSqlType(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant || value instanceof OffsetDateTime || value instanceof ZonedDateTime) {
            return Types.TIMESTAMP_WITH_TIMEZONE;
        }
        if (value instanceof LocalDateTime) {
            return Types.TIMESTAMP;
        }
        if (value instanceof LocalDate) {
            return Types.DATE;
        }
        if (value instanceof LocalTime) {
            return Types.TIME;
        }
        return null;
    }

    private static PGobject toJsonb(Object value) {
        try {
            PGobject pg = new PGobject();
            pg.setType("jsonb");
            pg.setValue(MAPPER.writeValueAsString(value));
            return pg;
        } catch (Exception e) {
            throw new RuntimeException("GeneratedBinderSupport: failed to serialize field to JSONB", e);
        }
    }
}
