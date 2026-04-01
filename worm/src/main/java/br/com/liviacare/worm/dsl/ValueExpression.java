package br.com.liviacare.worm.dsl;

import java.util.Objects;

final class ValueExpression<T> implements Expression<T> {

    private final T value;
    private final Class<T> type;

    ValueExpression(T value, Class<T> type) {
        this.value = value;
        this.type = Objects.requireNonNull(type, "type cannot be null");
    }

    T value() {
        return value;
    }

    @Override
    public Class<T> type() {
        return type;
    }

    @Override
    public int shapeHash() {
        return 31 * 17 + type.getName().hashCode();
    }
}

