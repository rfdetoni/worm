package com.github.rfdetoni.worm.dsl;

import java.util.Objects;

public final class BetweenPredicate<T> implements Predicate {

    private final Path<T> path;
    private final ValueExpression<T> lower;
    private final ValueExpression<T> upper;

    BetweenPredicate(Path<T> path, ValueExpression<T> lower, ValueExpression<T> upper) {
        this.path = Objects.requireNonNull(path, "path cannot be null");
        this.lower = Objects.requireNonNull(lower, "lower cannot be null");
        this.upper = Objects.requireNonNull(upper, "upper cannot be null");
    }

    Path<T> path() {
        return path;
    }

    ValueExpression<T> lower() {
        return lower;
    }

    ValueExpression<T> upper() {
        return upper;
    }

    @Override
    public int shapeHash() {
        int h = 29;
        h = 31 * h + path.shapeHash();
        h = 31 * h + lower.shapeHash();
        h = 31 * h + upper.shapeHash();
        return h;
    }
}

