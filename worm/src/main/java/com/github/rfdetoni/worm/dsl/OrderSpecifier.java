package com.github.rfdetoni.worm.dsl;

import java.util.Objects;

/**
 * ORDER BY element.
 */
public final class OrderSpecifier {

    public enum Direction {
        ASC,
        DESC
    }

    private final Path<?> path;
    private final Direction direction;

    public OrderSpecifier(Path<?> path, Direction direction) {
        this.path = Objects.requireNonNull(path, "path cannot be null");
        this.direction = Objects.requireNonNull(direction, "direction cannot be null");
    }

    public Path<?> path() {
        return path;
    }

    public Direction direction() {
        return direction;
    }

    int shapeHash() {
        int h = 17;
        h = 31 * h + path.shapeHash();
        h = 31 * h + direction.hashCode();
        return h;
    }
}

