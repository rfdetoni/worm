package com.github.rfdetoni.worm.dsl;

final class BooleanConstantPredicate implements Predicate {

    static final BooleanConstantPredicate TRUE = new BooleanConstantPredicate(true);
    static final BooleanConstantPredicate FALSE = new BooleanConstantPredicate(false);

    private final boolean value;

    private BooleanConstantPredicate(boolean value) {
        this.value = value;
    }

    boolean value() {
        return value;
    }

    @Override
    public int shapeHash() {
        return value ? 0x3A5F_1001 : 0x3A5F_1002;
    }
}

