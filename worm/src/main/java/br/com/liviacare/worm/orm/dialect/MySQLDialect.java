package br.com.liviacare.worm.orm.dialect;

import br.com.liviacare.worm.orm.mapping.ParamBinder;
import br.com.liviacare.worm.orm.registry.EntityMetadata;
import br.com.liviacare.worm.orm.sql.JdbcParameterNormalizer;
import br.com.liviacare.worm.orm.sql.WritePlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

public class MySQLDialect implements SqlDialect {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @Override
    public String applyPagination(String sql, int limit, int offset) {
        return sql + " LIMIT " + limit + " OFFSET " + offset;
    }

    @Override
    public String buildUpsertSql(EntityMetadata meta) {
        if (meta == null) throw new IllegalArgumentException("meta is null");
        String insert = meta.insertSql();
        List<String> updatable = meta.updatableColumns();
        String idCol = meta.idColumnName();
        StringBuilder set = new StringBuilder();
        for (String col : updatable) {
            if (col.equals(idCol)) continue;
            if (!set.isEmpty()) set.append(", ");
            set.append(col).append(" = VALUES(").append(col).append(")");
        }
        return set.isEmpty() ? insert : insert + " ON DUPLICATE KEY UPDATE " + set;
    }

    @Override
    public String ilikeExpression(String column) {
        return "LOWER(" + column + ") LIKE LOWER(?)";
    }

    @Override
    public String castToJson(String expression) {
        return "CAST(" + expression + " AS JSON)";
    }

    @Override
    public String generateUuidExpression() {
        return "UUID()";
    }

    @Override
    public boolean supportsReturning() {
        return false;
    }

    @Override
    public String returningClause(String... columns) {
        return "";
    }

    @Override
    public String currentTimestampExpression() {
        return "CURRENT_TIMESTAMP()";
    }

    @Override
    public ParamBinder createParamBinder(Class<?> entityClass, String sql, WritePlan.Slot[] slots, boolean hasVersion) {
        if (slots == null || slots.length == 0) {
            return null;
        }
        return (spec, entity) -> {
            for (WritePlan.Slot slot : slots) {
                Object value = switch (slot) {
                    case WritePlan.Slot.Field field -> normalizeField(field, entity);
                    case WritePlan.Slot.AuditNow auditNow -> auditNow.asLocalDateTime()
                            ? java.time.LocalDateTime.now()
                            : Timestamp.from(Instant.now());
                    case WritePlan.Slot.ActiveDefault activeDefault -> {
                        Object raw = activeDefault.getter().invoke(entity);
                        yield JdbcParameterNormalizer.normalize(
                                (activeDefault.forceFallback() || raw == null) ? activeDefault.fallback() : raw
                        );
                    }
                };
                spec = spec.param(value);
            }
            return spec;
        };
    }

    private Object normalizeField(WritePlan.Slot.Field field, Object entity) throws Throwable {
        Object raw = field.getter().invoke(entity);
        if (raw == null) {
            return null;
        }
        if (field.json()) {
            return MAPPER.writeValueAsString(raw);
        }
        return JdbcParameterNormalizer.normalize(raw);
    }
}
