package com.github.rfdetoni.worm.dsl;

import java.time.temporal.Temporal;

public final class DateTimePath<T extends Temporal & Comparable<? super T>> extends ComparablePath<T> {

    public DateTimePath(EntityPath<?> root, String column, Class<T> type) {
        super(root, column, type);
    }

    public Predicate before(T value) {
        return lt(value);
    }

    public Predicate after(T value) {
        return gt(value);
    }
}

