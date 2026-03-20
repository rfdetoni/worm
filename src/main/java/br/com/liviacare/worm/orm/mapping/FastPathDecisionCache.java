package br.com.liviacare.worm.orm.mapping;

import br.com.liviacare.worm.orm.registry.EntityMetadata;

import java.util.concurrent.ConcurrentHashMap;

public final class FastPathDecisionCache {

    private static final ConcurrentHashMap<Class<?>, Boolean> CACHE = new ConcurrentHashMap<>();

    private FastPathDecisionCache() {
    }

    public static boolean canUseFastPath(Class<?> entityClass, EntityMetadata<?> metadata) {
        return CACHE.computeIfAbsent(entityClass, ignored -> EntityPersisterFastPath.canUseFastPath(metadata));
    }

    public static void clear() {
        CACHE.clear();
    }

    public static int size() {
        return CACHE.size();
    }
}

