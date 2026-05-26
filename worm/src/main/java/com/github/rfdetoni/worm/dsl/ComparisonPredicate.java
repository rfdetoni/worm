package com.github.rfdetoni.worm.dsl;

import java.util.Objects;

public final class ComparisonPredicate implements Predicate {

    private final Expression<?> left;
    private final ComparisonOperator operator;
    private final Expression<?> right;

    ComparisonPredicate(Expression<?> left, ComparisonOperator operator, Expression<?> right) {
        this.left = Objects.requireNonNull(left, "left cannot be null");
        this.operator = Objects.requireNonNull(operator, "operator cannot be null");
        this.right = Objects.requireNonNull(right, "right cannot be null");
    }

    Expression<?> left() {
        return left;
    }

    ComparisonOperator operator() {
        return operator;
    }

    Expression<?> right() {
        return right;
    }

    @Override
    public int shapeHash() {
        int h = 19;
        h = 31 * h + left.shapeHash();
        h = 31 * h + operator.hashCode();
        h = 31 * h + right.shapeHash();
        return h;
    }
}

