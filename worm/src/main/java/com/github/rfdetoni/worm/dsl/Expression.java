package com.github.rfdetoni.worm.dsl;

/**
 * Base expression contract for the WORM DSL.
 *
 * @param <T> expression result type
 */
public interface Expression<T> {

    Class<T> type();

    /**
     * Deterministic structural hash used by query-shape keys.
     */
    int shapeHash();
}

