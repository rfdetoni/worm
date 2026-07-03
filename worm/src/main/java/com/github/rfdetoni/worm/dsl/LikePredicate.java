package com.github.rfdetoni.worm.dsl;

import java.util.Objects;

public final class LikePredicate implements Predicate {

    private final StringPath path;
    private final LikeMode mode;
    private final ValueExpression<String> value;

    LikePredicate(StringPath path, LikeMode mode, ValueExpression<String> value) {
        this.path = Objects.requireNonNull(path, "path cannot be null");
        this.mode = Objects.requireNonNull(mode, "mode cannot be null");
        this.value = Objects.requireNonNull(value, "value cannot be null");
    }

    StringPath path() {
        return path;
    }

    LikeMode mode() {
        return mode;
    }

    ValueExpression<String> value() {
        return value;
    }

    @Override
    public int shapeHash() {
        int h = 37;
        h = 31 * h + path.shapeHash();
        h = 31 * h + mode.hashCode();
        h = 31 * h + value.shapeHash();
        return h;
    }
}

