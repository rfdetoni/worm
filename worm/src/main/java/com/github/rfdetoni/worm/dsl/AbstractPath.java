package com.github.rfdetoni.worm.dsl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public abstract class AbstractPath<T> implements Path<T> {

    private final EntityPath<?> root;
    private final String column;
    private final Class<T> type;
    private final int shapeHash;

    protected AbstractPath(EntityPath<?> root, String column, Class<T> type) {
        this.root = Objects.requireNonNull(root, "root cannot be null");
        this.column = Objects.requireNonNull(column, "column cannot be null");
        this.type = Objects.requireNonNull(type, "type cannot be null");
        int h = 17;
        h = 31 * h + root.tableName().hashCode();
        h = 31 * h + root.alias().hashCode();
        h = 31 * h + column.hashCode();
        h = 31 * h + type.getName().hashCode();
        this.shapeHash = h;
    }

    @Override
    public final EntityPath<?> root() {
        return root;
    }

    @Override
    public final String column() {
        return column;
    }

    @Override
    public final Class<T> type() {
        return type;
    }

    @Override
    public final int shapeHash() {
        return shapeHash;
    }

    public Predicate eq(T value) {
        if (value == null) return isNull();
        return new ComparisonPredicate(this, ComparisonOperator.EQ, valueExpression(value));
    }

    public Predicate eq(Expression<? extends T> expression) {
        return new ComparisonPredicate(this, ComparisonOperator.EQ, (Expression<?>) Objects.requireNonNull(expression, "expression cannot be null"));
    }

    public Predicate ne(T value) {
        if (value == null) return isNotNull();
        return new ComparisonPredicate(this, ComparisonOperator.NE, valueExpression(value));
    }

    public Predicate ne(Expression<? extends T> expression) {
        return new ComparisonPredicate(this, ComparisonOperator.NE, (Expression<?>) Objects.requireNonNull(expression, "expression cannot be null"));
    }

    public Predicate isNull() {
        return new NullCheckPredicate(this, true);
    }

    public Predicate isNotNull() {
        return new NullCheckPredicate(this, false);
    }

    public Predicate in(Collection<? extends T> values) {
        if (values == null || values.isEmpty()) {
            return BooleanConstantPredicate.FALSE;
        }
        List<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            copy.add(value);
        }
        return new InPredicate<>(this, copy);
    }

    @SafeVarargs
    public final Predicate in(T... values) {
        if (values == null || values.length == 0) {
            return BooleanConstantPredicate.FALSE;
        }
        List<T> copy = new ArrayList<>(values.length);
        for (T value : values) {
            copy.add(value);
        }
        return new InPredicate<>(this, copy);
    }

    @SuppressWarnings("unchecked")
    protected final ValueExpression<T> valueExpression(T value) {
        return new ValueExpression<>(value, (Class<T>) value.getClass());
    }
}

