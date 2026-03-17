package br.com.liviacare.worm.orm.dialect;

import br.com.liviacare.worm.orm.registry.EntityMetadata;

import java.util.List;

public class MySQLDialect implements SqlDialect {

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
}
