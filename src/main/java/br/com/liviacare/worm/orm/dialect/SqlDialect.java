package br.com.liviacare.worm.orm.dialect;

import br.com.liviacare.worm.orm.registry.EntityMetadata;

public interface SqlDialect {
    String applyPagination(String sql, int limit, int offset);
    String buildUpsertSql(EntityMetadata meta);
    String ilikeExpression(String column);
    String castToJson(String expression);
    String generateUuidExpression();
    boolean supportsReturning();
    String returningClause(String... columns);
    String currentTimestampExpression();
}
