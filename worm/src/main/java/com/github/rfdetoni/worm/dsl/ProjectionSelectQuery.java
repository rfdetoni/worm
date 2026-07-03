package com.github.rfdetoni.worm.dsl;

import java.util.List;

public final class ProjectionSelectQuery extends AbstractSelectQuery<ProjectionSelectQuery> {

    ProjectionSelectQuery(EntityPath<?> from, Expression<?>[] projections) {
        super(from, projections, false);
        if (projections == null || projections.length == 0) {
            throw new IllegalArgumentException("Projection select requires at least one expression");
        }
    }

    public List<WormRow> fetch() {
        return QueryExecution.fetchRows(this);
    }

    public <R> List<R> fetchInto(Class<R> resultType) {
        return QueryExecution.fetchInto(this, resultType);
    }
}

