package br.com.liviacare.worm.api;

import br.com.liviacare.worm.orm.OrmManagerLocator;
import br.com.liviacare.worm.orm.OrmOperations;
import br.com.liviacare.worm.query.FilterBuilder;
import br.com.liviacare.worm.query.Pageable;
import br.com.liviacare.worm.query.Slice;

import java.util.List;
import java.util.Optional;

/**
 * ActiveRecord base class providing static query operations on entity types.
 *
 * Extend this class if you want the pattern:
 * <pre>
 *     List&lt;User&gt; all = User.all();
 *     Optional&lt;User&gt; user = User.byId(userId);
 * </pre>
 *
 * Usage in entity:
 * <pre>
 * &#64;DbTable("users")
 * public class User extends ActiveRecord&lt;User, UUID&gt; {
 *     &#64;DbId("id")
 *     private UUID id;
 *     // ... other fields
 * }
 *
 * // Then use:
 * List&lt;User&gt; all = User.all();
 * Optional&lt;User&gt; user = User.byId(userId);
 * List&lt;User&gt; filtered = User.all(new FilterBuilder().eq("active", true));
 * </pre>
 *
 * @param <T>  entity type
 * @param <ID> identifier type
 */
public abstract class ActiveRecord<T, ID> {

    protected static OrmOperations orm() {
        return OrmManagerLocator.getOrmManager();
    }

    /**
     * Returns the class type of this entity (must be implemented by subclass).
     * This is automatically called when using static methods.
     */
    @SuppressWarnings("unchecked")
    public static <T> Class<T> entityClass(Class<T> clazz) {
        return clazz;
    }

    /**
     * Find entity by ID.
     * <pre>
     *     Optional&lt;User&gt; user = User.byId(userId);
     * </pre>
     */
    protected static <T, ID> Optional<T> byId(Class<T> clazz, ID id) {
        return orm().findById(clazz, id);
    }

    /**
     * Count all records.
     * <pre>
     *     long total = User.count();
     * </pre>
     */
    protected static <T> long count(Class<T> clazz) {
        return orm().count(clazz);
    }

    /**
     * Count records matching the filter.
     * <pre>
     *     long active = User.count(new FilterBuilder().eq("active", true));
     * </pre>
     */
    protected static <T> long count(Class<T> clazz, FilterBuilder filter) {
        return orm().count(clazz, filter);
    }

    /**
     * Find one record by filter.
     * <pre>
     *     Optional&lt;User&gt; user = User.one(new FilterBuilder().eq("email", "test@example.com"));
     * </pre>
     */
    protected static <T> Optional<T> one(Class<T> clazz, FilterBuilder filter) {
        return orm().findOne(clazz, filter);
    }

    /**
     * Get all records (no filter).
     * <pre>
     *     List&lt;User&gt; all = User.all();
     * </pre>
     */
    protected static <T> List<T> all(Class<T> clazz) {
        return orm().findAll(clazz, new FilterBuilder());
    }

    /**
     * Get all records matching the filter.
     * <pre>
     *     List&lt;User&gt; active = User.all(new FilterBuilder().eq("active", true));
     * </pre>
     */
    protected static <T> List<T> all(Class<T> clazz, FilterBuilder filter) {
        return orm().findAll(clazz, filter);
    }

    /**
     * Get paginated results (no filter).
     * <pre>
     *     Slice&lt;User&gt; page = User.all(Pageable.of(0, 20));
     * </pre>
     */
    protected static <T> Slice<T> all(Class<T> clazz, Pageable pageable) {
        return orm().findAll(clazz, new FilterBuilder(), pageable);
    }

    /**
     * Get paginated results with filter.
     * <pre>
     *     Slice&lt;User&gt; page = User.all(
     *         new FilterBuilder().eq("active", true),
     *         Pageable.of(0, 20)
     *     );
     * </pre>
     */
    protected static <T> Slice<T> all(Class<T> clazz, FilterBuilder filter, Pageable pageable) {
        return orm().findAll(clazz, filter, pageable);
    }

    /**
     * Check if records exist.
     * <pre>
     *     boolean hasActiveUsers = User.exists(new FilterBuilder().eq("active", true));
     * </pre>
     */
    protected static <T> boolean exists(Class<T> clazz, FilterBuilder filter) {
        return orm().exists(clazz, filter);
    }

    /**
     * Check if any records exist.
     * <pre>
     *     boolean hasAnyUsers = User.exists();
     * </pre>
     */
    protected static <T> boolean exists(Class<T> clazz) {
        return exists(clazz, new FilterBuilder());
    }

    /**
     * Sum a numeric column.
     * <pre>
     *     Optional&lt;Long&gt; total = User.sum("age", Long.class, new FilterBuilder().eq("active", true));
     * </pre>
     */
    protected static <T, N extends Number> Optional<N> sum(Class<T> clazz, String column, Class<N> type, FilterBuilder filter) {
        return orm().sum(clazz, column, type, filter);
    }

    /**
     * Sum a numeric column (no filter).
     */
    protected static <T, N extends Number> Optional<N> sum(Class<T> clazz, String column, Class<N> type) {
        return sum(clazz, column, type, new FilterBuilder());
    }

    /**
     * Find the minimum value of a column.
     */
    protected static <T, N extends Number> Optional<N> min(Class<T> clazz, String column, Class<N> type, FilterBuilder filter) {
        return orm().min(clazz, column, type, filter);
    }

    /**
     * Find the minimum value of a column (no filter).
     */
    protected static <T, N extends Number> Optional<N> min(Class<T> clazz, String column, Class<N> type) {
        return min(clazz, column, type, new FilterBuilder());
    }

    /**
     * Find the maximum value of a column.
     */
    protected static <T, N extends Number> Optional<N> max(Class<T> clazz, String column, Class<N> type, FilterBuilder filter) {
        return orm().max(clazz, column, type, filter);
    }

    /**
     * Find the maximum value of a column (no filter).
     */
    protected static <T, N extends Number> Optional<N> max(Class<T> clazz, String column, Class<N> type) {
        return max(clazz, column, type, new FilterBuilder());
    }

    /**
     * Calculate the average of a column.
     */
    protected static <T> Optional<Double> avg(Class<T> clazz, String column, FilterBuilder filter) {
        return orm().avg(clazz, column, filter);
    }

    /**
     * Calculate the average of a column (no filter).
     */
    protected static <T> Optional<Double> avg(Class<T> clazz, String column) {
        return avg(clazz, column, new FilterBuilder());
    }

    /**
     * Find values from a specific column.
     * <pre>
     *     List&lt;String&gt; names = User.findColumn("name", String.class, new FilterBuilder().eq("active", true));
     * </pre>
     */
    protected static <T, C> List<C> findColumn(Class<T> clazz, String columnName, Class<C> type, FilterBuilder filter) {
        return orm().findColumn(clazz, columnName, type, filter);
    }

    /**
     * Find values from a specific column (no filter).
     */
    protected static <T, C> List<C> findColumn(Class<T> clazz, String columnName, Class<C> type) {
        return findColumn(clazz, columnName, type, new FilterBuilder());
    }

    /**
     * Find one value from a specific column.
     */
    protected static <T, C> Optional<C> findColumnOne(Class<T> clazz, String columnName, Class<C> type, FilterBuilder filter) {
        return orm().findColumnOne(clazz, columnName, type, filter);
    }

    /**
     * Find one value from a specific column (no filter).
     */
    protected static <T, C> Optional<C> findColumnOne(Class<T> clazz, String columnName, Class<C> type) {
        return findColumnOne(clazz, columnName, type, new FilterBuilder());
    }
}

