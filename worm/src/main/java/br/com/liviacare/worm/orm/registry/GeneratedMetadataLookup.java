package br.com.liviacare.worm.orm.registry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Discovers compile-time generated metadata factories via {@link ServiceLoader}.
 *
 * <p>The lookup map is built once at class initialization to avoid repeated
 * ServiceLoader iteration on hot paths.
 */
final class GeneratedMetadataLookup {

    private static final Map<Class<?>, GeneratedEntityMetadataFactory<?>> FACTORIES = loadFactories();

    private GeneratedMetadataLookup() {
    }

    private static Map<Class<?>, GeneratedEntityMetadataFactory<?>> loadFactories() {
        Map<Class<?>, GeneratedEntityMetadataFactory<?>> map = new LinkedHashMap<>();
        ServiceLoader<GeneratedEntityMetadataFactory> loader = ServiceLoader.load(GeneratedEntityMetadataFactory.class);
        for (GeneratedEntityMetadataFactory<?> factory : loader) {
            map.putIfAbsent(factory.entityClass(), factory);
        }
        return Map.copyOf(map);
    }

    @SuppressWarnings("unchecked")
    static <T> GeneratedEntityMetadataFactory<T> forEntity(Class<T> entityClass) {
        return (GeneratedEntityMetadataFactory<T>) FACTORIES.get(entityClass);
    }
}

