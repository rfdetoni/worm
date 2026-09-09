package com.github.rfdetoni.worm.dsl;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Cache of rendered DSL SQL plans by structural query shape.
 */
public final class QueryPlanCache {

    private static final int DEFAULT_MAX_ENTRIES = 4096;
    private static final ConcurrentHashMap<QueryShape, QueryPlan> CACHE = new ConcurrentHashMap<>(256);
    private static final ConcurrentLinkedQueue<QueryShape> INSERTION_ORDER = new ConcurrentLinkedQueue<>();
    private static final LongAdder HITS = new LongAdder();
    private static final LongAdder MISSES = new LongAdder();

    private static volatile int maxEntries = DEFAULT_MAX_ENTRIES;

    private QueryPlanCache() {
    }

    public static void configureMaxEntries(int configuredMaxEntries) {
        maxEntries = configuredMaxEntries > 0 ? configuredMaxEntries : DEFAULT_MAX_ENTRIES;
        trimToLimit();
    }

    public static QueryPlan get(QueryShape shape) {
        QueryPlan existing = CACHE.get(shape);
        if (existing != null) {
            HITS.increment();
        }
        return existing;
    }

    public static QueryPlan put(QueryShape shape, QueryPlan plan) {
        QueryPlan winner = CACHE.putIfAbsent(shape, plan);
        if (winner != null) {
            HITS.increment();
            return winner;
        }

        MISSES.increment();
        INSERTION_ORDER.offer(shape);
        trimToLimit();
        return plan;
    }

    public static QueryPlan getOrBuild(QueryShape shape, Supplier<QueryPlan> builder) {
        QueryPlan cached = get(shape);
        if (cached != null) {
            return cached;
        }
        return put(shape, builder.get());
    }

    private static void trimToLimit() {
        while (CACHE.size() > maxEntries) {
            QueryShape oldest = INSERTION_ORDER.poll();
            if (oldest == null) {
                return;
            }
            CACHE.remove(oldest);
        }
    }

    public static int size() {
        return CACHE.size();
    }

    public static int maxEntries() {
        return maxEntries;
    }

    public static long hitCount() {
        return HITS.sum();
    }

    public static long missCount() {
        return MISSES.sum();
    }

    public static double hitRatio() {
        long hits = HITS.sum();
        long misses = MISSES.sum();
        long total = hits + misses;
        return total == 0 ? 0d : ((double) hits) / total;
    }

    public static void clear() {
        CACHE.clear();
        INSERTION_ORDER.clear();
        HITS.reset();
        MISSES.reset();
    }
}
