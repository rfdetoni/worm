package br.com.liviacare.worm.dsl;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Cache of rendered SQL plans by shape.
 */
public final class QueryPlanCache {

    private static final ConcurrentHashMap<QueryShape, QueryPlan> CACHE = new ConcurrentHashMap<>(256);
    private static final LongAdder HITS = new LongAdder();
    private static final LongAdder MISSES = new LongAdder();

    private QueryPlanCache() {
    }

    public static QueryPlan getOrBuild(QueryShape shape, Supplier<QueryPlan> builder) {
        QueryPlan existing = CACHE.get(shape);
        if (existing != null) {
            HITS.increment();
            return existing;
        }
        QueryPlan built = builder.get();
        QueryPlan winner = CACHE.putIfAbsent(shape, built);
        if (winner == null) {
            MISSES.increment();
            return built;
        }
        HITS.increment();
        return winner;
    }

    public static int size() {
        return CACHE.size();
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
        HITS.reset();
        MISSES.reset();
    }
}

