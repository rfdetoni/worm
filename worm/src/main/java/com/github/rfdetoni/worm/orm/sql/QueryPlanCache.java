package com.github.rfdetoni.worm.orm.sql;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * Thread-safe, application-scoped cache for compiled SQL query plans.
 *
 * <p>The cache is deliberately bounded. Pageable queries may render a distinct SQL
 * plan for each LIMIT/OFFSET pair, so an unbounded cache can retain an arbitrary
 * number of plans when callers traverse deep page ranges.</p>
 */
public final class QueryPlanCache {

    private static final int DEFAULT_MAX_ENTRIES = 4096;
    private static final ConcurrentHashMap<QueryPlanKey, String> CACHE = new ConcurrentHashMap<>(256);
    private static final ConcurrentLinkedQueue<QueryPlanKey> INSERTION_ORDER = new ConcurrentLinkedQueue<>();

    private static volatile int maxEntries = DEFAULT_MAX_ENTRIES;

    private QueryPlanCache() {
    }

    /**
     * Configure the maximum number of cached query shapes.
     * Values lower than one restore the safe default.
     */
    public static void configureMaxEntries(int configuredMaxEntries) {
        maxEntries = configuredMaxEntries > 0 ? configuredMaxEntries : DEFAULT_MAX_ENTRIES;
        trimToLimit();
    }

    /**
     * Returns the cached SQL for the given key, computing and storing it on first use.
     */
    public static String get(QueryPlanKey key, Supplier<String> builder) {
        String existing = CACHE.get(key);
        if (existing != null) {
            return existing;
        }

        String computed = builder.get();
        String raced = CACHE.putIfAbsent(key, computed);
        if (raced != null) {
            return raced;
        }

        INSERTION_ORDER.offer(key);
        trimToLimit();
        return computed;
    }

    private static void trimToLimit() {
        while (CACHE.size() > maxEntries) {
            QueryPlanKey oldest = INSERTION_ORDER.poll();
            if (oldest == null) {
                return;
            }
            CACHE.remove(oldest);
        }
    }

    /**
     * Discards all cached plans. Useful in tests that reset EntityRegistry.
     */
    public static void clear() {
        CACHE.clear();
        INSERTION_ORDER.clear();
    }

    /** Returns the current number of cached plans. */
    public static int size() {
        return CACHE.size();
    }

    /** Returns the configured upper bound for diagnostics and tests. */
    public static int maxEntries() {
        return maxEntries;
    }
}
