package br.com.liviacare.worm.orm.mapping;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Static per-entity binder generated at compile-time.
 *
 * @param <T> entity type
 */
public interface EntityBinder<T> {

    void bindInsert(JdbcClient.StatementSpec spec, T entity);

    void bindUpdate(JdbcClient.StatementSpec spec, T entity);
}
