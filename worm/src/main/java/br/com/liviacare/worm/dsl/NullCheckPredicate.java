package br.com.liviacare.worm.dsl;

import java.util.Objects;

public final class NullCheckPredicate implements Predicate {

    private final Path<?> path;
    private final boolean isNull;

    NullCheckPredicate(Path<?> path, boolean isNull) {
        this.path = Objects.requireNonNull(path, "path cannot be null");
        this.isNull = isNull;
    }

    Path<?> path() {
        return path;
    }

    boolean isNull() {
        return isNull;
    }

    @Override
    public int shapeHash() {
        int h = 23;
        h = 31 * h + path.shapeHash();
        h = 31 * h + (isNull ? 1 : 2);
        return h;
    }
}

