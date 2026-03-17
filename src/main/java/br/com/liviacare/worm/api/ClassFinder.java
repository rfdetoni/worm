package br.com.liviacare.worm.api;

import br.com.liviacare.worm.orm.OrmManagerLocator;
import br.com.liviacare.worm.orm.OrmOperations;
import br.com.liviacare.worm.query.FilterBuilder;
import br.com.liviacare.worm.query.Pageable;
import br.com.liviacare.worm.query.Slice;

import java.util.List;
import java.util.Optional;

/**
 * Fluent query entry-point for a specific entity class.
 *
 * @param <T> entity type
 */
public final class ClassFinder<T> {

    private final Class<T> clazz;
    private final OrmOperations orm;
    private String mainAlias;

    public ClassFinder(Class<T> clazz) {
        this.clazz = clazz;
        this.orm = OrmManagerLocator.getOrmManager();
    }

    public ClassFinder<T> alias(String alias) {
        this.mainAlias = (alias == null || alias.isBlank()) ? null : alias.trim();
        return this;
    }

    private FilterBuilder withAlias(FilterBuilder filter) {
        if (filter == null) filter = new FilterBuilder();
        if (mainAlias != null) filter.mainAlias(mainAlias);
        return filter;
    }

    public Optional<T> byId(Object id) {
        return orm.findById(clazz, id, mainAlias);
    }

    public Optional<T> one(FilterBuilder filter) {
        return orm.findOne(clazz, withAlias(filter));
    }

    public List<T> all() {
        return orm.findAll(clazz, withAlias(null));
    }

    public List<T> all(FilterBuilder filter) {
        return orm.findAll(clazz, withAlias(filter));
    }

    public Slice<T> all(Pageable pageable) {
        return orm.findAll(clazz, withAlias(null), pageable);
    }

    public Slice<T> all(FilterBuilder filter, Pageable pageable) {
        return orm.findAll(clazz, withAlias(filter), pageable);
    }

    public long count() {
        return orm.count(clazz, withAlias(null));
    }

    public long count(FilterBuilder filter) {
        return orm.count(clazz, withAlias(filter));
    }

    public boolean exists() {
        return orm.exists(clazz, withAlias(null));
    }

    public boolean exists(FilterBuilder filter) {
        return orm.exists(clazz, withAlias(filter));
    }

    public <N extends Number> Optional<N> sum(String column, Class<N> type, FilterBuilder filter) {
        return orm.sum(clazz, column, type, withAlias(filter));
    }

    public <N extends Number> Optional<N> min(String column, Class<N> type, FilterBuilder filter) {
        return orm.min(clazz, column, type, withAlias(filter));
    }

    public <N extends Number> Optional<N> max(String column, Class<N> type, FilterBuilder filter) {
        return orm.max(clazz, column, type, withAlias(filter));
    }

    public Optional<Double> avg(String column, FilterBuilder filter) {
        return orm.avg(clazz, column, withAlias(filter));
    }

    public <C> List<C> findColumn(String columnName, Class<C> type, FilterBuilder filter) {
        return orm.findColumn(clazz, columnName, type, withAlias(filter));
    }

    public <C> List<C> findColumn(String columnName, Class<C> type) {
        return findColumn(columnName, type, new FilterBuilder());
    }

    public <C> Optional<C> findColumnOne(String columnName, Class<C> type, FilterBuilder filter) {
        return orm.findColumnOne(clazz, columnName, type, withAlias(filter));
    }

    public <C> Optional<C> findColumnOne(String columnName, Class<C> type) {
        return findColumnOne(columnName, type, new FilterBuilder());
    }

    public <R> List<R> queryList(String baseSql, Class<R> type, FilterBuilder filter) {
        return orm.queryList(clazz, baseSql, type, withAlias(filter));
    }

    public <R> Optional<R> queryOne(String baseSql, Class<R> type, FilterBuilder filter) {
        return orm.queryOne(clazz, baseSql, type, withAlias(filter));
    }

    public Optional<String> jsonPathQueryFirst(String column, String jsonPath, FilterBuilder filter) {
        return orm.jsonPathQueryFirst(clazz, column, jsonPath, withAlias(filter));
    }

    public Optional<String> jsonPathQueryFirst(String column, String jsonPath) {
        return jsonPathQueryFirst(column, jsonPath, new FilterBuilder());
    }

    public Optional<String> jsonPathQueryFirstWithVars(String column, String jsonPath, Object varsJson, FilterBuilder filter) {
        return orm.jsonPathQueryFirstWithVars(clazz, column, jsonPath, varsJson, withAlias(filter));
    }

    public Optional<String> jsonPathQueryFirstWithVars(String column, String jsonPath, Object varsJson) {
        return jsonPathQueryFirstWithVars(column, jsonPath, varsJson, new FilterBuilder());
    }

    public Optional<String> jsonPathQueryArray(String column, String jsonPath, FilterBuilder filter) {
        return orm.jsonPathQueryArray(clazz, column, jsonPath, withAlias(filter));
    }

    public Optional<String> jsonPathQueryArray(String column, String jsonPath) {
        return jsonPathQueryArray(column, jsonPath, new FilterBuilder());
    }
}
