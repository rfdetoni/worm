package com.github.rfdetoni.worm.dsl;

public final class NumberPath<N extends Number & Comparable<? super N>> extends ComparablePath<N> {

    public NumberPath(EntityPath<?> root, String column, Class<N> type) {
        super(root, column, type);
    }
}

