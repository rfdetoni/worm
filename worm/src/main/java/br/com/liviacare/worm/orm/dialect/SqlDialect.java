package br.com.liviacare.worm.orm.dialect;

import br.com.liviacare.worm.orm.mapping.BulkWriter;
import br.com.liviacare.worm.orm.mapping.ParamBinder;
import br.com.liviacare.worm.orm.registry.EntityMetadata;
import br.com.liviacare.worm.orm.sql.WritePlan;

import javax.sql.DataSource;

public interface SqlDialect {
    String applyPagination(String sql, int limit, int offset);
    String buildUpsertSql(EntityMetadata meta);
    String ilikeExpression(String column);
    String castToJson(String expression);
    String generateUuidExpression();
    boolean supportsReturning();
    String returningClause(String... columns);
    String currentTimestampExpression();

    /**
     * Creates the optimal {@link BulkWriter} for this dialect.
     *
     * <p>Returns {@code null} by default, meaning standard JDBC batching is used.
     * Override in dialect implementations that support driver-level bulk mechanisms
     * (e.g. PostgreSQL COPY, MySQL multi-row INSERT).
     *
     * @param dataSource      live DataSource
     * @param copyThreshold   min list size to use COPY (Postgres-specific)
     * @param unnestThreshold min list size to use unnest arrays (Postgres-specific)
     * @return a {@link BulkWriter} instance, or {@code null} if not supported
     */
    default BulkWriter createBulkWriter(DataSource dataSource, int copyThreshold, int unnestThreshold) {
        return null;
    }

    /**
     * Creates a dialect-specific {@link ParamBinder} for a compiled {@link WritePlan}.
     *
     * <p>Return {@code null} to use WORM's default compiled binder generated from
     * write-plan slots.
     */
    default ParamBinder createParamBinder(Class<?> entityClass, String sql, WritePlan.Slot[] slots, boolean hasVersion) {
        return null;
    }
}
