package br.com.liviacare.worm.dsl;

/**
 * Boolean expression node.
 */
public interface Predicate extends Expression<Boolean> {

    @Override
    default Class<Boolean> type() {
        return Boolean.class;
    }

    default Predicate and(Predicate other) {
        return JunctionPredicate.and(this, other);
    }

    default Predicate or(Predicate other) {
        return JunctionPredicate.or(this, other);
    }

    default Predicate not() {
        return new NotPredicate(this);
    }
}

