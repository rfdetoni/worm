package br.com.liviacare.worm.api;

import br.com.liviacare.worm.orm.OrmManagerLocator;
import br.com.liviacare.worm.orm.OrmOperations;

import java.util.List;

/**
 * Mixin interface that allows an entity to save or update itself.
 *
 * @param <T> entity type
 */
public interface Persistable<T extends Persistable<T>> {

    @SuppressWarnings("unchecked")
    default T save() {
        orm().save(this);
        return (T) this;
    }

    @SuppressWarnings("unchecked")
    default T update() {
        orm().update(this);
        return (T) this;
    }

    static <E extends Persistable<E>> E save(E entity) {
        orm().save(entity);
        return entity;
    }

    static <E extends Persistable<E>> E update(E entity) {
        orm().update(entity);
        return entity;
    }

    static <E extends Persistable<E>> List<E> saveAll(List<E> entities) {
        orm().saveAll(entities);
        return entities;
    }

    static <E extends Persistable<E>> List<E> updateAll(List<E> entities) {
        orm().updateAll(entities);
        return entities;
    }

    private static OrmOperations orm() {
        return OrmManagerLocator.getOrmManager();
    }
}
