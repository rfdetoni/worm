package br.com.liviacare.worm.dsl;

import java.util.Objects;

public final class JoinOnStep<Q extends AbstractSelectQuery<Q>> {

    private final Q query;
    private final JoinType type;
    private final EntityPath<?> target;

    JoinOnStep(Q query, JoinType type, EntityPath<?> target) {
        this.query = Objects.requireNonNull(query, "query cannot be null");
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.target = Objects.requireNonNull(target, "target cannot be null");
    }

    public Q on(Predicate predicate) {
        return query.addJoin(type, target, Objects.requireNonNull(predicate, "predicate cannot be null"));
    }
}

