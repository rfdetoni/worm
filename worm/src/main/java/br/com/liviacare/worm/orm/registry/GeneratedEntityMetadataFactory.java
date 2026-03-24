package br.com.liviacare.worm.orm.registry;

import br.com.liviacare.worm.orm.converter.ConverterRegistry;
import br.com.liviacare.worm.orm.dialect.SqlDialect;

/**
 * Contract implemented by annotation-processor generated metadata factories.
 *
 * <p>This is the bridge between compile-time code generation and the current
 * runtime registry. The first scaffold implementation may still delegate to
 * {@link EntityMetadata#of(Class, SqlDialect, ConverterRegistry)}, but later
 * iterations can return fully static metadata instances with zero reflection.
 *
 * @param <T> entity type
 */
public interface GeneratedEntityMetadataFactory<T> {

    /** Returns the entity class handled by this factory. */
    Class<T> entityClass();

    /**
     * Creates the metadata instance for {@link #entityClass()}.
     *
     * @param dialect SQL dialect active for the current ORM session
     * @param converterRegistry converter registry active for the current ORM session
     * @return metadata instance for the entity
     */
    EntityMetadata<T> create(SqlDialect dialect, ConverterRegistry converterRegistry);
}

