package br.com.liviacare.worm.dsl;

import java.util.Objects;

public final class NotPredicate implements Predicate {

    private final Predicate inner;

    NotPredicate(Predicate inner) {
        this.inner = Objects.requireNonNull(inner, "inner cannot be null");
    }

    Predicate inner() {
        return inner;
    }

    @Override
    public int shapeHash() {
        return 31 * 43 + inner.shapeHash();
    }
}

