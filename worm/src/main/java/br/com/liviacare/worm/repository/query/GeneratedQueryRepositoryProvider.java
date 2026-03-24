package br.com.liviacare.worm.repository.query;

import br.com.liviacare.worm.orm.OrmOperations;

/** Contract implemented by APT-generated query repository providers. */
public interface GeneratedQueryRepositoryProvider<T> {
    Class<T> repositoryInterface();
    T create(OrmOperations ormOperations);
}

