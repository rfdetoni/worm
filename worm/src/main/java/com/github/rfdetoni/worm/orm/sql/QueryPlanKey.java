package com.github.rfdetoni.worm.orm.sql;

/**
 * Immutable structural key for the {@link QueryPlanCache}.
 *
 * <p>Two queries share the same plan when they produce identical SQL with only
 * bind-parameter values differing.  The key therefore captures the <em>shape</em>
 * of the query — entity class, WHERE template (with {@code ?} placeholders),
 * join list, ordering, pagination, and modifiers — but never the actual values.
 */
record QueryPlanKey(
        /** Entity whose metadata drives SQL construction. */
        Class<?> entityClass,
        /** Raw WHERE clause string as produced by FilterBuilder (contains only '?' — no values). */
        String whereTemplate,
        /** Structural hash of the FilterBuilder join list (type + table + alias + ON template). */
        int joinsHashCode,
        /** ORDER BY token resolved at build time (null = no order). */
        String orderShape,
        /** LIMIT (page size), or -1 when no pageable. */
        int pageSize,
        /** OFFSET derived from page number, or 0 when no pageable. */
        int pageOffset,
        /** Whether to fetch one extra row for cursor-style "has next page" detection. */
        boolean fetchOneMore,
        /** True when all joins are suppressed via FilterBuilder.notJoin(). */
        boolean noJoin,
        /** True when the soft-delete predicate is intentionally suppressed. */
        boolean ignoreSoftDelete,
        /** Structural fingerprint of CTE and window-function declarations. */
        String cteShape,
        /** "select", "count", or "exists". */
        String queryType
) {
}

