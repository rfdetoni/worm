package br.com.liviacare.worm.repository;

import br.com.liviacare.worm.query.FilterBuilder;
import br.com.liviacare.worm.query.Pageable;
import br.com.liviacare.worm.query.Slice;

import java.util.List;
import java.util.Optional;

/**
 * Generic repository interface that defines the contract for repositories built
 * on top of the WORM(Weightless ORM). Implementations can delegate to GenericRepository.
 */
public interface LjfRepository<T, ID> {

    void save(T entity);

    void update(T entity);

    void delete(T entity);

    Optional<T> findById(ID id);

    List<T> findAll(FilterBuilder filter);

    List<T> findAll();

    Slice<T> findAll(FilterBuilder filter, Pageable pageable);

    /**
     * Batch save with default chunk size
     */
    void saveAll(List<T> entities);

    /**
     * Batch save with explicit chunk size
     */
    void saveAll(List<T> entities, int chunkSize);

    // ------------------------------------------------------------------
    // Convenience contract for single-column projections
    // ------------------------------------------------------------------

    <C> List<C> findColumn(String columnName, Class<C> type, FilterBuilder filter);

    <C> List<C> findColumn(String columnName, Class<C> type);

    <C> Optional<C> findColumnOne(String columnName, Class<C> type, FilterBuilder filter);

    <C> Optional<C> findColumnOne(String columnName, Class<C> type);
}
