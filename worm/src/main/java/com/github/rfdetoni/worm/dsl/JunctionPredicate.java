package com.github.rfdetoni.worm.dsl;

import java.util.Objects;

public final class JunctionPredicate implements Predicate {

    public enum Type {
        AND,
        OR
    }

    private final Type type;
    private final Predicate[] predicates;

    private JunctionPredicate(Type type, Predicate[] predicates) {
        this.type = type;
        this.predicates = predicates;
    }

    public static Predicate and(Predicate left, Predicate right) {
        return combine(Type.AND, left, right);
    }

    public static Predicate or(Predicate left, Predicate right) {
        return combine(Type.OR, left, right);
    }

    private static Predicate combine(Type type, Predicate left, Predicate right) {
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(left, "left cannot be null");
        Objects.requireNonNull(right, "right cannot be null");

        if (left == BooleanConstantPredicate.TRUE && type == Type.AND) return right;
        if (right == BooleanConstantPredicate.TRUE && type == Type.AND) return left;
        if (left == BooleanConstantPredicate.FALSE && type == Type.OR) return right;
        if (right == BooleanConstantPredicate.FALSE && type == Type.OR) return left;

        int leftSize = (left instanceof JunctionPredicate j && j.type == type) ? j.predicates.length : 1;
        int rightSize = (right instanceof JunctionPredicate j && j.type == type) ? j.predicates.length : 1;
        Predicate[] flat = new Predicate[leftSize + rightSize];
        int pos = 0;
        if (left instanceof JunctionPredicate j && j.type == type) {
            System.arraycopy(j.predicates, 0, flat, pos, j.predicates.length);
            pos += j.predicates.length;
        } else {
            flat[pos++] = left;
        }
        if (right instanceof JunctionPredicate j && j.type == type) {
            System.arraycopy(j.predicates, 0, flat, pos, j.predicates.length);
        } else {
            flat[pos] = right;
        }
        return new JunctionPredicate(type, flat);
    }

    Type junctionType() {
        return type;
    }

    Predicate[] predicates() {
        return predicates;
    }

    @Override
    public int shapeHash() {
        int h = 41;
        h = 31 * h + type.hashCode();
        for (Predicate p : predicates) {
            h = 31 * h + p.shapeHash();
        }
        return h;
    }
}
