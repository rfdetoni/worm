package br.com.liviacare.worm.dsl;

import java.util.Objects;

public class ComparablePath<T extends Comparable<? super T>> extends AbstractPath<T> {

    public ComparablePath(EntityPath<?> root, String column, Class<T> type) {
        super(root, column, type);
    }

    public Predicate gt(T value) {
        return new ComparisonPredicate(this, ComparisonOperator.GT, valueExpression(Objects.requireNonNull(value, "value cannot be null")));
    }

    public Predicate gt(Expression<? extends T> expression) {
        return new ComparisonPredicate(this, ComparisonOperator.GT, (Expression<?>) Objects.requireNonNull(expression, "expression cannot be null"));
    }

    public Predicate goe(T value) {
        return new ComparisonPredicate(this, ComparisonOperator.GOE, valueExpression(Objects.requireNonNull(value, "value cannot be null")));
    }

    public Predicate goe(Expression<? extends T> expression) {
        return new ComparisonPredicate(this, ComparisonOperator.GOE, (Expression<?>) Objects.requireNonNull(expression, "expression cannot be null"));
    }

    public Predicate lt(T value) {
        return new ComparisonPredicate(this, ComparisonOperator.LT, valueExpression(Objects.requireNonNull(value, "value cannot be null")));
    }

    public Predicate lt(Expression<? extends T> expression) {
        return new ComparisonPredicate(this, ComparisonOperator.LT, (Expression<?>) Objects.requireNonNull(expression, "expression cannot be null"));
    }

    public Predicate loe(T value) {
        return new ComparisonPredicate(this, ComparisonOperator.LOE, valueExpression(Objects.requireNonNull(value, "value cannot be null")));
    }

    public Predicate loe(Expression<? extends T> expression) {
        return new ComparisonPredicate(this, ComparisonOperator.LOE, (Expression<?>) Objects.requireNonNull(expression, "expression cannot be null"));
    }

    public Predicate between(T lower, T upper) {
        Objects.requireNonNull(lower, "lower cannot be null");
        Objects.requireNonNull(upper, "upper cannot be null");
        return new BetweenPredicate<>(this, valueExpression(lower), valueExpression(upper));
    }
}

