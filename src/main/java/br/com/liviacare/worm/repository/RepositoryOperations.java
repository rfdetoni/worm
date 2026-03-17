package br.com.liviacare.worm.repository;

import br.com.liviacare.worm.query.FilterBuilder;
import br.com.liviacare.worm.query.Pageable;
import br.com.liviacare.worm.query.Slice;

import java.util.List;
import java.util.Optional;

/**
 * Minimal repository operations interface to reduce exposure of concrete types.
 */
public interface RepositoryOperations<T, ID> {

    void save(T entity);

    void update(T entity);

    void delete(T entity);

    Optional<T> findById(ID id);

    List<T> findAll(FilterBuilder filter);

    List<T> findAll();

    Slice<T> findAll(FilterBuilder filter, Pageable pageable);

    void saveAll(List<T> entities);

    void saveAll(List<T> entities, int chunkSize);

    <C> List<C> findColumn(String columnName, Class<C> type, FilterBuilder filter);

    <C> List<C> findColumn(String columnName, Class<C> type);

    <C> Optional<C> findColumnOne(String columnName, Class<C> type, FilterBuilder filter);

    <C> Optional<C> findColumnOne(String columnName, Class<C> type);
}

