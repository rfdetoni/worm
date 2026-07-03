package com.github.rfdetoni.worm.repository.query;

import com.github.rfdetoni.worm.orm.OrmOperations;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;

final class GeneratedQueryRepositoryLookup {

    private static final Map<Class<?>, GeneratedQueryRepositoryProvider<?>> PROVIDERS = loadProviders();

    private GeneratedQueryRepositoryLookup() {
    }

    private static Map<Class<?>, GeneratedQueryRepositoryProvider<?>> loadProviders() {
        Map<Class<?>, GeneratedQueryRepositoryProvider<?>> map = new LinkedHashMap<>();
        ServiceLoader<GeneratedQueryRepositoryProvider> loader = ServiceLoader.load(GeneratedQueryRepositoryProvider.class);
        for (GeneratedQueryRepositoryProvider<?> provider : loader) {
            map.putIfAbsent(provider.repositoryInterface(), provider);
        }
        return Map.copyOf(map);
    }

    @SuppressWarnings("unchecked")
    static <T> T create(Class<T> repositoryInterface, OrmOperations ormOperations) {
        GeneratedQueryRepositoryProvider<T> provider = (GeneratedQueryRepositoryProvider<T>) PROVIDERS.get(repositoryInterface);
        return provider == null ? null : provider.create(ormOperations);
    }
}

