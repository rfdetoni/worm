package br.com.liviacare.worm.orm.mapping;

import br.com.liviacare.worm.orm.registry.EntityMetadata;

import java.util.Arrays;
import java.util.List;

/**
 * Fast-path adapter for write parameter extraction.
 *
 * <p>It currently reuses the array-based extraction from {@link EntityPersister},
 * which avoids the extra {@code List -> Object[]} conversion on batch paths and
 * provides a stable integration point for {@link br.com.liviacare.worm.orm.OrmManager}.
 */
public final class EntityPersisterFastPath {

    private EntityPersisterFastPath() {
    }

    public static boolean canUseFastPath(EntityMetadata<?> metadata) {
        return metadata != null && metadata.idGetter() != null;
    }

    public static <T> List<Object> insertValuesFast(T entity, EntityMetadata<T> metadata) {
        return Arrays.asList(insertValuesArrayFast(entity, metadata));
    }

    public static <T> List<Object> updateValuesFast(T entity, EntityMetadata<T> metadata, Object id) {
        return Arrays.asList(updateValuesArrayFast(entity, metadata, id));
    }

    public static <T> Object[] insertValuesArrayFast(T entity, EntityMetadata<T> metadata) {
        return EntityPersister.insertValuesArray(entity, metadata);
    }

    public static <T> Object[] updateValuesArrayFast(T entity, EntityMetadata<T> metadata, Object id) {
        return EntityPersister.updateValuesArray(entity, metadata, id);
    }
}

