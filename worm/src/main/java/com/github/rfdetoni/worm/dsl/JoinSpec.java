package com.github.rfdetoni.worm.dsl;

import java.util.Objects;

final class JoinSpec {

    private final JoinType type;
    private final EntityPath<?> target;
    private final Predicate on;

    JoinSpec(JoinType type, EntityPath<?> target, Predicate on) {
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.target = Objects.requireNonNull(target, "target cannot be null");
        this.on = Objects.requireNonNull(on, "on cannot be null");
    }

    JoinType type() {
        return type;
    }

    EntityPath<?> target() {
        return target;
    }

    Predicate on() {
        return on;
    }

    int shapeHash() {
        int h = 17;
        h = 31 * h + type.hashCode();
        h = 31 * h + target.shapeHash();
        h = 31 * h + on.shapeHash();
        return h;
    }
}

