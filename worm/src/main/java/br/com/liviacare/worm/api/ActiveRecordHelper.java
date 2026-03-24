package br.com.liviacare.worm.api;

import br.com.liviacare.worm.orm.OrmManagerLocator;
import br.com.liviacare.worm.orm.OrmOperations;
import br.com.liviacare.worm.query.FilterBuilder;
import br.com.liviacare.worm.query.Pageable;
import br.com.liviacare.worm.query.Slice;

import java.util.List;
import java.util.Optional;

/**
 * Fluent helper for ActiveRecord-style queries on a specific entity class.
 *
 * This is useful for creating extension methods in entity classes via static fields.
 *
 * Example usage in an entity:
 * <pre>
 * &#64;DbTable("users")
 * public class User implements Persistable&lt;User&gt; {
 *     &#64;DbId("id")
 *     private UUID id;
 *
 *     // ActiveRecord helper
 *     public static class Query {
 *         public static List&lt;User&gt; all() {
 *             return all(new FilterBuilder());
 *         }
 *
 *         public static List&lt;User&gt; all(FilterBuilder filter) {
 *             return orm().findAll(User.class, filter);
 *         }
 *
 *         public static Optional&lt;User&gt; byId(UUID id) {
 *             return orm().findById(User.class, id);
 *         }
 *
 *         public static long count() {
 *             return orm().count(User.class);
 *         }
 *
 *         private static OrmOperations orm() {
 *             return OrmManagerLocator.getOrmManager();
 *         }
 *     }
 *
 *     // Then use:
 *     // User.Query.all();
 *     // User.Query.byId(userId);
 * }
 * </pre>
 *
 * Or, for a simpler pattern, use {@link ActiveRecord} base class instead.
 */
public class ActiveRecordHelper {

    /**
     * Creates a query helper for a specific entity class.
     *
     * This is a factory method that returns a stateless object containing
     * common query methods. It's designed for flexibility if you need custom
     * logic per entity.
     *
     * @param <T>  the entity type
     * @param <ID> the ID type
     * @param entityClass the class of the entity
     * @return a query helper instance
     */
    public static <T, ID> StatelessQueryHelper<T, ID> createQueryHelper(Class<T> entityClass) {
        return new StatelessQueryHelper<>(entityClass);
    }

    /**
     * Stateless query helper wrapping an entity class and providing ActiveRecord-style methods.
     */
    public static class StatelessQueryHelper<T, ID> {
        private final Class<T> entityClass;

        public StatelessQueryHelper(Class<T> entityClass) {
            this.entityClass = entityClass;
        }

        public Optional<T> byId(ID id) {
            return orm().findById(entityClass, id);
        }

        public long count() {
            return orm().count(entityClass);
        }

        public long count(FilterBuilder filter) {
            return orm().count(entityClass, filter);
        }

        public Optional<T> one(FilterBuilder filter) {
            return orm().findOne(entityClass, filter);
        }

        public List<T> all() {
            return orm().findAll(entityClass, new FilterBuilder());
        }

        public List<T> all(FilterBuilder filter) {
            return orm().findAll(entityClass, filter);
        }

        public Slice<T> all(Pageable pageable) {
            return orm().findAll(entityClass, new FilterBuilder(), pageable);
        }

        public Slice<T> all(FilterBuilder filter, Pageable pageable) {
            return orm().findAll(entityClass, filter, pageable);
        }

        public boolean exists(FilterBuilder filter) {
            return orm().exists(entityClass, filter);
        }

        public boolean exists() {
            return exists(new FilterBuilder());
        }

        public <N extends Number> Optional<N> sum(String column, Class<N> type, FilterBuilder filter) {
            return orm().sum(entityClass, column, type, filter);
        }

        public <N extends Number> Optional<N> sum(String column, Class<N> type) {
            return sum(column, type, new FilterBuilder());
        }

        public <N extends Number> Optional<N> min(String column, Class<N> type, FilterBuilder filter) {
            return orm().min(entityClass, column, type, filter);
        }

        public <N extends Number> Optional<N> min(String column, Class<N> type) {
            return min(column, type, new FilterBuilder());
        }

        public <N extends Number> Optional<N> max(String column, Class<N> type, FilterBuilder filter) {
            return orm().max(entityClass, column, type, filter);
        }

        public <N extends Number> Optional<N> max(String column, Class<N> type) {
            return max(column, type, new FilterBuilder());
        }

        public Optional<Double> avg(String column, FilterBuilder filter) {
            return orm().avg(entityClass, column, filter);
        }

        public Optional<Double> avg(String column) {
            return avg(column, new FilterBuilder());
        }

        public <C> List<C> findColumn(String columnName, Class<C> type, FilterBuilder filter) {
            return orm().findColumn(entityClass, columnName, type, filter);
        }

        public <C> List<C> findColumn(String columnName, Class<C> type) {
            return findColumn(columnName, type, new FilterBuilder());
        }

        public <C> Optional<C> findColumnOne(String columnName, Class<C> type, FilterBuilder filter) {
            return orm().findColumnOne(entityClass, columnName, type, filter);
        }

        public <C> Optional<C> findColumnOne(String columnName, Class<C> type) {
            return findColumnOne(columnName, type, new FilterBuilder());
        }

        private static OrmOperations orm() {
            return OrmManagerLocator.getOrmManager();
        }
    }
}

