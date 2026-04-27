package br.com.liviacare.worm.dsl;

/**
 * Rendered SQL plan reused for a query shape.
 */
public record QueryPlan(
        String sql,
        int projectionCount
) {
}

