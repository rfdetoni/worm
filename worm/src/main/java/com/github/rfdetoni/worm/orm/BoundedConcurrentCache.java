package com.github.rfdetoni.worm.orm;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

/**
 * Small application-scoped cache for cheap derived ORM artifacts whose key space can be dynamic.
 *
 * <p>The eviction policy is intentionally simple FIFO: these caches store strings whose rebuild
 * cost is tiny, so bounded memory and low synchronization overhead are more valuable than a full
 * LRU implementation.</p>
 */
final class BoundedConcurrentCache<K, V> {

    private final ConcurrentHashMap<K, V> cache = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<K> insertionOrder = new ConcurrentLinkedQueue<>();
    private final int maxEntries;

    BoundedConcurrentCache(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be greater than zero");
        }
        this.maxEntries = maxEntries;
    }

    V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(mappingFunction, "mappingFunction");

        V existing = cache.get(key);
        if (existing != null) {
            return existing;
        }

        V computed = Objects.requireNonNull(mappingFunction.apply(key), "computed cache value");
        V raced = cache.putIfAbsent(key, computed);
        if (raced != null) {
            return raced;
        }

        insertionOrder.offer(key);
        trimToLimit();
        return computed;
    }

    int size() {
        return cache.size();
    }

    private void trimToLimit() {
        while (cache.size() > maxEntries) {
            K oldest = insertionOrder.poll();
            if (oldest == null) {
                return;
            }
            cache.remove(oldest);
        }
    }
}
