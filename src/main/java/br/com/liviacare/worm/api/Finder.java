package br.com.liviacare.worm.api;

import br.com.liviacare.worm.orm.OrmManagerLocator;
import br.com.liviacare.worm.orm.OrmOperations;
import br.com.liviacare.worm.query.FilterBuilder;
import br.com.liviacare.worm.query.Pageable;
import br.com.liviacare.worm.query.Slice;

import java.util.List;
import java.util.Optional;

/**
 * Mixin interface providing query operations on an entity type.
 *
 * @param <T>  entity type
 * @param <ID> identifier type
 */
public interface Finder<T, ID> {

    Class<T> entityClass();

    default ClassFinder<T> alias(String alias) {
        return Finder.of(entityClass()).alias(alias);
    }

    default Optional<T> byId(ID id) {
        return orm().findById(entityClass(), id);
    }

    default long count() {
        return orm().count(entityClass());
    }

    default long count(FilterBuilder filter) {
        return orm().count(entityClass(), filter);
    }

    default Optional<T> one(FilterBuilder filter) {
        return orm().findOne(entityClass(), filter);
    }

    default List<T> all(FilterBuilder filter) {
        return orm().findAll(entityClass(), filter);
    }

    default List<T> all() {
        return orm().findAll(entityClass(), new FilterBuilder());
    }

    default Slice<T> all(Pageable pageable) {
        return orm().findAll(entityClass(), new FilterBuilder(), pageable);
    }

    default Slice<T> all(FilterBuilder filter, Pageable pageable) {
        return orm().findAll(entityClass(), filter, pageable);
    }

    default boolean exists(FilterBuilder filter) {
        return orm().exists(entityClass(), filter);
    }

    default boolean exists() {
        return exists(new FilterBuilder());
    }

    default <N extends Number> Optional<N> sum(String column, Class<N> type, FilterBuilder filter) {
        return orm().sum(entityClass(), column, type, filter);
    }

    default <N extends Number> Optional<N> sum(String column, Class<N> type) {
        return sum(column, type, new FilterBuilder());
    }

    default <N extends Number> Optional<N> min(String column, Class<N> type, FilterBuilder filter) {
        return orm().min(entityClass(), column, type, filter);
    }

    default <N extends Number> Optional<N> min(String column, Class<N> type) {
        return min(column, type, new FilterBuilder());
    }

    default <N extends Number> Optional<N> max(String column, Class<N> type, FilterBuilder filter) {
        return orm().max(entityClass(), column, type, filter);
    }

    default <N extends Number> Optional<N> max(String column, Class<N> type) {
        return max(column, type, new FilterBuilder());
    }

    default Optional<Double> avg(String column, FilterBuilder filter) {
        return orm().avg(entityClass(), column, filter);
    }

    default Optional<Double> avg(String column) {
        return avg(column, new FilterBuilder());
    }

    default <C> List<C> findColumn(String columnName, Class<C> type, FilterBuilder filter) {
        return orm().findColumn(entityClass(), columnName, type, filter);
    }

    default <C> List<C> findColumn(String columnName, Class<C> type) {
        return findColumn(columnName, type, new FilterBuilder());
    }

    default <C> Optional<C> findColumnOne(String columnName, Class<C> type, FilterBuilder filter) {
        return orm().findColumnOne(entityClass(), columnName, type, filter);
    }

    default <C> Optional<C> findColumnOne(String columnName, Class<C> type) {
        return findColumnOne(columnName, type, new FilterBuilder());
    }

    default Optional<String> jsonPathQueryFirst(String column, String jsonPath, FilterBuilder filter) {
        return orm().jsonPathQueryFirst(entityClass(), column, jsonPath, filter);
    }

    default Optional<String> jsonPathQueryFirst(String column, String jsonPath) {
        return jsonPathQueryFirst(column, jsonPath, new FilterBuilder());
    }

    default Optional<String> jsonPathQueryFirstWithVars(String column, String jsonPath, Object varsJson, FilterBuilder filter) {
        return orm().jsonPathQueryFirstWithVars(entityClass(), column, jsonPath, varsJson, filter);
    }

    default Optional<String> jsonPathQueryFirstWithVars(String column, String jsonPath, Object varsJson) {
        return jsonPathQueryFirstWithVars(column, jsonPath, varsJson, new FilterBuilder());
    }

    default Optional<String> jsonPathQueryArray(String column, String jsonPath, FilterBuilder filter) {
        return orm().jsonPathQueryArray(entityClass(), column, jsonPath, filter);
    }

    default Optional<String> jsonPathQueryArray(String column, String jsonPath) {
        return jsonPathQueryArray(column, jsonPath, new FilterBuilder());
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    static <T, ID> Optional<T> byId(Class<T> clazz, ID id) {
        return orm().findById(clazz, id);
    }

    static <T> long count(Class<T> clazz) {
        return orm().count(clazz);
    }

    static <T> long count(Class<T> clazz, FilterBuilder filter) {
        return orm().count(clazz, filter);
    }

    static <T> Optional<T> one(Class<T> clazz, FilterBuilder filter) {
        return orm().findOne(clazz, filter);
    }

    static <T> List<T> all(Class<T> clazz, FilterBuilder filter) {
        return orm().findAll(clazz, filter);
    }

    static <T> List<T> all(Class<T> clazz) {
        return orm().findAll(clazz, new FilterBuilder());
    }

    static <T> Slice<T> all(Class<T> clazz, Pageable pageable) {
        return orm().findAll(clazz, new FilterBuilder(), pageable);
    }

    static <T> Slice<T> all(Class<T> clazz, FilterBuilder filter, Pageable pageable) {
        return orm().findAll(clazz, filter, pageable);
    }

    static <T, C> List<C> findColumn(Class<T> clazz, String columnName, Class<C> type, FilterBuilder filter) {
        return orm().findColumn(clazz, columnName, type, filter);
    }

    static <T, C> List<C> findColumn(Class<T> clazz, String columnName, Class<C> type) {
        return findColumn(clazz, columnName, type, new FilterBuilder());
    }

    static <T, C> Optional<C> findColumnOne(Class<T> clazz, String columnName, Class<C> type, FilterBuilder filter) {
        return orm().findColumnOne(clazz, columnName, type, filter);
    }

    static <T, C> Optional<C> findColumnOne(Class<T> clazz, String columnName, Class<C> type) {
        return findColumnOne(clazz, columnName, type, new FilterBuilder());
    }

    static <R> List<R> nativeQueryList(String sql, Class<R> resultClass, Object... params) {
        return orm().executeRaw(sql, resultClass, params);
    }

    static <R> Optional<R> nativeQuery(String sql, Class<R> resultClass, Object... params) {
        List<R> list = orm().executeRaw(sql, resultClass, params);
        return list.isEmpty() ? Optional.empty() : Optional.ofNullable(list.get(0));
    }

    static <T> ClassFinder<T> of(Class<T> clazz) {
        return new ClassFinder<>(clazz);
    }

    private static OrmOperations orm() {
        return OrmManagerLocator.getOrmManager();
    }
}

