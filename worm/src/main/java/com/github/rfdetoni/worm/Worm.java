package com.github.rfdetoni.worm;

import com.github.rfdetoni.worm.dsl.EntityPath;
import com.github.rfdetoni.worm.dsl.EntitySelectQuery;
import com.github.rfdetoni.worm.dsl.Expression;
import com.github.rfdetoni.worm.dsl.ProjectionSelectStart;

import java.util.Objects;

/**
 * WORM DSL entry point.
 */
public final class Worm {

    private Worm() {
    }

    public static <T> EntitySelectQuery<T> selectFrom(EntityPath<T> root) {
        return new EntitySelectQuery<>(Objects.requireNonNull(root, "root cannot be null"));
    }

    public static ProjectionSelectStart select(Expression<?>... expressions) {
        if (expressions == null || expressions.length == 0) {
            throw new IllegalArgumentException("select(...) requires at least one expression");
        }
        return new ProjectionSelectStart(expressions);
    }
}

