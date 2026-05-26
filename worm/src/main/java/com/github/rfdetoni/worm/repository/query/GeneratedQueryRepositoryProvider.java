package com.github.rfdetoni.worm.repository.query;

import com.github.rfdetoni.worm.orm.OrmOperations;

/** Contract implemented by APT-generated query repository providers. */
public interface GeneratedQueryRepositoryProvider<T> {
    Class<T> repositoryInterface();
    T create(OrmOperations ormOperations);
}

