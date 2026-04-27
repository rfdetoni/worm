package br.com.liviacare.worm.dsl;

import java.util.List;

public final class EntitySelectQuery<T> extends AbstractSelectQuery<EntitySelectQuery<T>> {

    public EntitySelectQuery(EntityPath<T> from) {
        super(from, null, true);
    }

    @SuppressWarnings("unchecked")
    private EntityPath<T> root() {
        return (EntityPath<T>) from();
    }

    public List<T> fetch() {
        return QueryExecution.fetchEntity(this, root().entityType());
    }
}
