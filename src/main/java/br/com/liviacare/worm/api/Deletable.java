package br.com.liviacare.worm.api;

import br.com.liviacare.worm.orm.OrmManagerLocator;
import br.com.liviacare.worm.orm.OrmOperations;

import java.util.List;

/**
 * Mixin interface that allows an entity to delete itself.
 *
 * @param <T>  entity type
 * @param <ID> identifier type
 */
public interface Deletable<T extends Deletable<T, ID>, ID> {

    static <E, ID> void deleteById(Class<E> clazz, ID id) {
        orm().deleteById(clazz, id);
    }

    static void deleteAll(List<? extends Deletable<?, ?>> entities) {
        orm().deleteAll(entities);
    }

    ID getId();

    default void delete() {
        orm().delete(this);
    }

    private static OrmOperations orm() {
        return OrmManagerLocator.getOrmManager();
    }
}
