package br.com.liviacare.worm.orm.dialect;

import br.com.liviacare.worm.orm.mapping.ParamBinder;
import br.com.liviacare.worm.orm.mapping.BulkWriter;
import br.com.liviacare.worm.orm.mapping.PostgresBulkWriter;
import br.com.liviacare.worm.orm.registry.EntityMetadata;
import br.com.liviacare.worm.orm.sql.WritePlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.lang.invoke.MethodHandle;
import java.util.StringJoiner;

public class PostgresDialect implements SqlDialect {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());
    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

    // Hot-loop slot opcodes (byte for compact arrays and switch dispatch)
    private static final byte OP_FIELD_PLAIN = 1;
    private static final byte OP_FIELD_ENUM = 2;
    private static final byte OP_FIELD_JSON = 3;
    private static final byte OP_FIELD_ENUM_JSON = 4;
    private static final byte OP_AUDIT_INSTANT = 5;
    private static final byte OP_AUDIT_LOCAL_DATE_TIME = 6;
    private static final byte OP_ACTIVE_FALLBACK = 7;
    private static final byte OP_ACTIVE_READ_OR_FALLBACK = 8;

    @Override
    public String applyPagination(String sql, int limit, int offset) {
        return sql + " LIMIT " + limit + " OFFSET " + offset;
    }

    @Override
    public String buildUpsertSql(EntityMetadata meta) {
        if (meta == null) throw new IllegalArgumentException("meta is null");
        String insert = meta.insertSql();
        String idCol = meta.idColumnName();
        java.util.List<String> updatable = meta.updatableColumns();
        StringJoiner sj = new StringJoiner(", ");
        for (String col : updatable) {
            if (col.equals(idCol)) continue;
            sj.add(col + " = EXCLUDED." + col);
        }
        if (sj.length() == 0) return insert;
        return insert + " ON CONFLICT (" + idCol + ") DO UPDATE SET " + sj;
    }

    @Override
    public String ilikeExpression(String column) {
        return column + " ILIKE ?";
    }

    @Override
    public String castToJson(String expression) {
        return expression + "::jsonb";
    }

    @Override
    public String generateUuidExpression() {
        return "gen_random_uuid()";
    }

    @Override
    public boolean supportsReturning() {
        return true;
    }

    @Override
    public String returningClause(String... columns) {
        if (columns == null || columns.length == 0) return "";
        StringJoiner sj = new StringJoiner(", ", " RETURNING ", "");
        for (String c : columns) sj.add(c);
        return sj.toString();
    }

    @Override
    public String currentTimestampExpression() {
        return "NOW()";
    }

    /**
     * Returns a {@link PostgresBulkWriter} that uses COPY (above {@code copyThreshold})
     * and unnest arrays (above {@code unnestThreshold}) for peak throughput.
     *
     * @return {@code null} when {@code dataSource} is null (no bulk support without a DataSource)
     */
    @Override
    public BulkWriter createBulkWriter(DataSource dataSource, int copyThreshold, int unnestThreshold) {
        if (dataSource == null) return null;
        return new PostgresBulkWriter(dataSource, copyThreshold, unnestThreshold);
    }

    @Override
    public ParamBinder createParamBinder(Class<?> entityClass, String sql, WritePlan.Slot[] slots, boolean hasVersion) {
        if (slots == null || slots.length == 0) return null;

        final byte[] ops = new byte[slots.length];
        final MethodHandle[] getters = new MethodHandle[slots.length];
        final Object[] fallbacks = new Object[slots.length];

        for (int i = 0; i < slots.length; i++) {
            WritePlan.Slot slot = slots[i];
            switch (slot) {
                case WritePlan.Slot.Field f -> {
                    getters[i] = f.getter();
                    if (f.json() && f.isEnum()) ops[i] = OP_FIELD_ENUM_JSON;
                    else if (f.json()) ops[i] = OP_FIELD_JSON;
                    else if (f.isEnum()) ops[i] = OP_FIELD_ENUM;
                    else ops[i] = OP_FIELD_PLAIN;
                }
                case WritePlan.Slot.AuditNow a -> {
                    ops[i] = a.asLocalDateTime() ? OP_AUDIT_LOCAL_DATE_TIME : OP_AUDIT_INSTANT;
                }
                case WritePlan.Slot.ActiveDefault d -> {
                    getters[i] = d.getter();
                    fallbacks[i] = d.fallback();
                    ops[i] = d.forceFallback() ? OP_ACTIVE_FALLBACK : OP_ACTIVE_READ_OR_FALLBACK;
                }
            }
        }

        return (spec, entity) -> {
            final Instant now = Instant.now();
            JdbcClient.StatementSpec current = spec;
            for (int i = 0; i < ops.length; i++) {
                Object value;
                switch (ops[i]) {
                    case OP_FIELD_PLAIN -> value = getters[i].invoke(entity);
                    case OP_FIELD_ENUM -> {
                        Object raw = getters[i].invoke(entity);
                        value = (raw == null) ? null : ((Enum<?>) raw).name();
                    }
                    case OP_FIELD_JSON -> {
                        Object raw = getters[i].invoke(entity);
                        value = (raw == null) ? null : toJsonb(raw);
                    }
                    case OP_FIELD_ENUM_JSON -> {
                        Object raw = getters[i].invoke(entity);
                        value = (raw == null) ? null : toJsonb(((Enum<?>) raw).name());
                    }
                    case OP_AUDIT_INSTANT -> value = now;
                    case OP_AUDIT_LOCAL_DATE_TIME -> value = LocalDateTime.ofInstant(now, SYSTEM_ZONE);
                    case OP_ACTIVE_FALLBACK -> value = fallbacks[i];
                    case OP_ACTIVE_READ_OR_FALLBACK -> {
                        Object raw = getters[i].invoke(entity);
                        value = (raw == null) ? fallbacks[i] : raw;
                    }
                    default -> throw new IllegalStateException("Unsupported Postgres binder opcode: " + ops[i]);
                }
                current = current.param(value);
            }
            return current;
        };
    }

    private static PGobject toJsonb(Object value) {
        try {
            PGobject pg = new PGobject();
            pg.setType("jsonb");
            pg.setValue(MAPPER.writeValueAsString(value));
            return pg;
        } catch (Exception e) {
            throw new RuntimeException("PostgresDialect ParamBinder: failed to serialize field to JSONB", e);
        }
    }
}
