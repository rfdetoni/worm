package br.com.liviacare.worm.orm.sql;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Thread-safe, application-scoped cache for compiled SQL query plans.
 *
 * <p>SQL generation (alias resolution, join AST construction, predicate inlining) is
 * relatively expensive when the same query shape is issued thousands of times per
 * second.  This cache maps a structural {@link QueryPlanKey} to the final SQL string
 * so the generation only runs once per unique shape.
 *
 * <p>The cache is intentionally unbounded because the number of distinct query shapes
 * in a well-structured application is finite and typically small (one per
 * service/repository method).
 *
 * <p><strong>Thread safety:</strong> {@link ConcurrentHashMap#computeIfAbsent} is used
 * throughout; under high contention the supplier may be evaluated more than once, but
 * SQL construction is pure and idempotent so duplicate computation is harmless.
 */
public final class QueryPlanCache {

    private static final ConcurrentHashMap<QueryPlanKey, String> CACHE = new ConcurrentHashMap<>(256);

    private QueryPlanCache() {}

    /**
     * Returns the cached SQL for the given key, computing and storing it on the first call.
     *
     * @param key     structural descriptor of the query
     * @param builder called exactly once (per key) to produce the SQL string
     * @return cached SQL string, never null
     */
    public static String get(QueryPlanKey key, Supplier<String> builder) {
        return CACHE.computeIfAbsent(key, k -> builder.get());
    }

    /**
     * Discards all cached plans.  Useful in tests that reset {@code EntityRegistry}.
     */
    public static void clear() {
        CACHE.clear();
    }

    /** Returns the current number of cached plans — useful for diagnostics. */
    public static int size() {
        return CACHE.size();
    }
}

