package br.com.liviacare.worm.dsl;

/**
 * Structural key for compiled DSL query plans.
 */
public record QueryShape(
        Class<?> rootEntityType,
        String rootTable,
        String rootAlias,
        String projectionShape,
        String joinsShape,
        String whereShape,
        String orderShape,
        boolean hasLimit,
        boolean hasOffset,
        boolean selectEntity
) {
}

