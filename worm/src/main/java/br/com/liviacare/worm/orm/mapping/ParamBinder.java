package br.com.liviacare.worm.orm.mapping;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * SPI for compiled write-parameter binding in the zero-allocation write path.
 *
 * <p>An implementation receives a {@link JdbcClient.StatementSpec} and the entity being
 * written, then binds each positional {@code ?} parameter by calling
 * {@link JdbcClient.StatementSpec#param(Object)} exactly once per placeholder, in
 * placeholder order.
 *
 * <p>The default implementation is produced at metadata-build time by
 * {@link br.com.liviacare.worm.orm.sql.WritePlan} and stored on
 * {@link br.com.liviacare.worm.orm.registry.EntityMetadata}.  Custom implementations
 * can replace or augment the binding behaviour for specific entity types by registering
 * them via the {@link br.com.liviacare.worm.orm.dialect.SqlDialect} SPI.
 *
 * <p><b>Contract:</b> implementations must be stateless and thread-safe — a single
 * instance is shared across all concurrent write operations for the same entity class.
 */
@FunctionalInterface
public interface ParamBinder {

    /**
     * Binds all parameters from {@code entity} into the JDBC statement spec.
     *
     * <p>Implementations must call {@link JdbcClient.StatementSpec#param(Object)}
     * exactly once for every {@code ?} placeholder in the SQL, in placeholder order.
     * The spec returned by each {@code param()} call must be used for the next
     * {@code param()} invocation (Spring's {@code DefaultStatementSpec} is fluent).
     *
     * @param spec   the input statement spec; implementations must chain/replace it
     *               while binding values
     * @param entity the entity being written; the concrete type matches the
     *               {@link br.com.liviacare.worm.orm.registry.EntityMetadata} the
     *               binder was built from
     * @return the final statement spec after all params are bound
     * @throws Throwable if any {@link java.lang.invoke.MethodHandle} invocation fails
     */
    JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Object entity) throws Throwable;
}

