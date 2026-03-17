package br.com.liviacare.worm.orm;

import br.com.liviacare.worm.query.FilterBuilder;
import br.com.liviacare.worm.query.Page;
import br.com.liviacare.worm.query.Pageable;
import br.com.liviacare.worm.query.Slice;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Optional;

/**
 * Primary interface for all ORM operations.
 * Obtain an instance via {@link OrmManagerLocator#getOrmManager()}.
 */
public interface OrmOperations {

    // ── Write ────────────────────────────────────────────────────────────────

    <T> void save(T entity);

    <T> int[] saveAll(List<T> entities);

    <T> int[] saveAllBatch(List<T> entities);

    <T> void update(T entity);

    <T> int[] updateAll(List<T> entities);

    <T> int[] updateAllBatch(List<T> entities);

    <T> void delete(T entity);

    <T, I> void deleteById(Class<T> clazz, I id);

    <T> int[] deleteAll(List<T> entities);

    <T> int[] deleteAllBatch(List<T> entities);

    <T> int[] upsertAll(List<T> entities);

    <T> int[] upsertAllBatch(List<T> entities);

    // ── Read ─────────────────────────────────────────────────────────────────

    <T, I> Optional<T> findById(Class<T> clazz, I id);

    <T, I> Optional<T> findById(Class<T> clazz, I id, String mainAlias);

    <T, P> Optional<P> findById(Class<T> entityClass, Object id, Class<P> projectionClass);

    <T> Optional<T> findOne(Class<T> clazz, FilterBuilder filter);

    <T, P> Optional<P> findOne(Class<T> entityClass, FilterBuilder filter, Class<P> projectionClass);

    <T> List<T> findAll(Class<T> clazz, FilterBuilder filter);

    <T> Slice<T> findAll(Class<T> clazz, FilterBuilder filter, Pageable pageable);

    <T, P> List<P> findAll(Class<T> entityClass, FilterBuilder filter, Class<P> projectionClass);

    <T> Page<T> findPage(Class<T> clazz, FilterBuilder filter, Pageable pageable);

    <T> List<T> findAllWithCte(Class<T> entityClass, FilterBuilder filterWithCte);

    <T, P> List<P> findAllWithCte(Class<T> entityClass, FilterBuilder filterWithCte, Class<P> projectionClass);

    // ── Existence & aggregates ────────────────────────────────────────────────

    <T> boolean exists(Class<T> clazz, FilterBuilder filter);

    <T, I> boolean existsById(Class<T> clazz, I id);

    <T> long count(Class<T> clazz);

    <T> long count(Class<T> clazz, FilterBuilder filter);

    <T, N extends Number> Optional<N> sum(Class<T> clazz, String column, Class<N> type, FilterBuilder filter);

    <T, N extends Number> Optional<N> min(Class<T> clazz, String column, Class<N> type, FilterBuilder filter);

    <T, N extends Number> Optional<N> max(Class<T> clazz, String column, Class<N> type, FilterBuilder filter);

    <T> Optional<Double> avg(Class<T> clazz, String column, FilterBuilder filter);

    // ── Projections ───────────────────────────────────────────────────────────

    <T, C> List<C> findColumn(Class<T> clazz, String columnName, Class<C> type, FilterBuilder filter);

    <T, C> Optional<C> findColumnOne(Class<T> clazz, String columnName, Class<C> type, FilterBuilder filter);

    // ── Raw & JSON ────────────────────────────────────────────────────────────

    <T, R> List<R> queryList(Class<T> entityClass, String baseSql, Class<R> type, FilterBuilder filter);

    <T, R> Optional<R> queryOne(Class<T> entityClass, String baseSql, Class<R> type, FilterBuilder filter);

    <T> Optional<String> jsonPathQueryFirst(Class<T> clazz, String column, String jsonPath, FilterBuilder filter);

    <T> Optional<String> jsonPathQueryFirstWithVars(Class<T> clazz, String column, String jsonPath, Object varsJson, FilterBuilder filter);

    <T> Optional<String> jsonPathQueryArray(Class<T> clazz, String column, String jsonPath, FilterBuilder filter);

    <T> List<T> executeRaw(String sql, Class<T> resultClass, Object... params);

    /** Exposes the underlying {@link JdbcClient} for advanced use cases. */
    JdbcClient client();
}

