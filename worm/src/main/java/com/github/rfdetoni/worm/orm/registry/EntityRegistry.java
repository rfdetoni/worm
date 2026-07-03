package com.github.rfdetoni.worm.orm.registry;

import com.github.rfdetoni.worm.orm.converter.ConverterRegistry;
import com.github.rfdetoni.worm.orm.dialect.SqlDialect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class EntityRegistry {

    private static volatile Map<Class<?>, EntityMetadata<?>> METADATA_CACHE = bootstrapMetadataCache();
    private static final Map<Class<?>, ProjectionMetadata> PROJECTION_CACHE = new ConcurrentHashMap<>();
    private static volatile SqlDialect sqlDialect;
    private static volatile ConverterRegistry converterRegistry;

    @SuppressWarnings("unchecked")
    public static <T> EntityMetadata<T> getMetadata(Class<T> entityClass) {
        return (EntityMetadata<T>) METADATA_CACHE.get(entityClass);
    }

    @SuppressWarnings("unchecked")
    public static <P> ProjectionMetadata getProjectionMetadata(Class<P> projectionClass, EntityMetadata<?> source) {
        return PROJECTION_CACHE.computeIfAbsent(projectionClass, pc -> ProjectionMetadata.of(pc, source, converterRegistry));
    }

    public static void setSqlDialect(SqlDialect dialect) {
        sqlDialect = dialect;
        refreshMetadataCache();
    }

    public static void setConverterRegistry(ConverterRegistry registry) {
        converterRegistry = registry;
        refreshMetadataCache();
    }

    public static java.util.Collection<EntityMetadata<?>> getAllMetadata() {
        return METADATA_CACHE.values();
    }

    private static synchronized void refreshMetadataCache() {
        METADATA_CACHE = bootstrapMetadataCache();
        PROJECTION_CACHE.clear();
    }

    private static Map<Class<?>, EntityMetadata<?>> bootstrapMetadataCache() {
        Map<Class<?>, EntityMetadata<?>> metadataByType = new LinkedHashMap<>();
        for (GeneratedEntityMetadataFactory<?> factory : GeneratedMetadataLookup.factories().values()) {
            EntityMetadata<?> metadata = factory.create(sqlDialect, converterRegistry);
            if (metadata != null) {
                metadataByType.put(factory.entityClass(), metadata);
            }
        }
        return Map.copyOf(metadataByType);
    }
}
