package br.com.liviacare.worm.dsl;

import java.time.temporal.Temporal;

public final class DatePath<T extends Temporal & Comparable<? super T>> extends ComparablePath<T> {

    public DatePath(EntityPath<?> root, String column, Class<T> type) {
        super(root, column, type);
    }
}

