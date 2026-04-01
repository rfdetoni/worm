package br.com.liviacare.worm.dsl;

import java.util.Objects;

public final class ProjectionSelectStart {

    private final Expression<?>[] projections;

    public ProjectionSelectStart(Expression<?>[] projections) {
        this.projections = Objects.requireNonNull(projections, "projections cannot be null");
    }

    public ProjectionSelectQuery from(EntityPath<?> from) {
        return new ProjectionSelectQuery(from, projections);
    }
}
