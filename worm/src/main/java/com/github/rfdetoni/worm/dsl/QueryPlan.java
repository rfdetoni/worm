package com.github.rfdetoni.worm.dsl;

/**
 * Rendered SQL plan reused for a query shape.
 */
public record QueryPlan(
        String sql,
        int projectionCount
) {
}

