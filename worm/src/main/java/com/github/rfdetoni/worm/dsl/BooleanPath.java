package com.github.rfdetoni.worm.dsl;

public final class BooleanPath extends AbstractPath<Boolean> {

    public BooleanPath(EntityPath<?> root, String column) {
        super(root, column, Boolean.class);
    }

    public Predicate isTrue() {
        return eq(Boolean.TRUE);
    }

    public Predicate isFalse() {
        return eq(Boolean.FALSE);
    }
}

