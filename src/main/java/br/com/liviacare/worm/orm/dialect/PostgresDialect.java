package br.com.liviacare.worm.orm.dialect;

import br.com.liviacare.worm.orm.registry.EntityMetadata;

import java.util.StringJoiner;

public class PostgresDialect implements SqlDialect {

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
}
