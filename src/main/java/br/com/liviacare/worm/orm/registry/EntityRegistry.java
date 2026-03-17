package br.com.liviacare.worm.orm.registry;

import br.com.liviacare.worm.orm.converter.ConverterRegistry;
import br.com.liviacare.worm.orm.dialect.SqlDialect;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class EntityRegistry {

    private static final Map<Class<?>, EntityMetadata<?>> METADATA_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, ProjectionMetadata> PROJECTION_CACHE = new ConcurrentHashMap<>();
    private static volatile SqlDialect sqlDialect;
    private static volatile ConverterRegistry converterRegistry;

    @SuppressWarnings("unchecked")
    public static <T> EntityMetadata<T> getMetadata(Class<T> entityClass) {
        return (EntityMetadata<T>) METADATA_CACHE.computeIfAbsent(entityClass, cls -> EntityMetadata.of(cls, sqlDialect, converterRegistry));
    }

    @SuppressWarnings("unchecked")
    public static <P> ProjectionMetadata getProjectionMetadata(Class<P> projectionClass, EntityMetadata<?> source) {
        return PROJECTION_CACHE.computeIfAbsent(projectionClass, pc -> ProjectionMetadata.of(pc, source, converterRegistry));
    }

    public static void setSqlDialect(SqlDialect dialect) {
        sqlDialect = dialect;
    }

    public static void setConverterRegistry(ConverterRegistry registry) {
        converterRegistry = registry;
    }

    public static java.util.Collection<EntityMetadata<?>> getAllMetadata() {
        return java.util.Collections.unmodifiableCollection(METADATA_CACHE.values());
    }
}
