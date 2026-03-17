package br.com.liviacare.worm.repository;

import br.com.liviacare.worm.orm.OrmOperations;
import br.com.liviacare.worm.orm.mapping.EntityPersister;
import br.com.liviacare.worm.orm.registry.EntityMetadata;
import br.com.liviacare.worm.orm.registry.EntityRegistry;
import br.com.liviacare.worm.orm.sql.SqlConstants;
import br.com.liviacare.worm.query.FilterBuilder;
import br.com.liviacare.worm.query.Pageable;
import br.com.liviacare.worm.query.Slice;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Generic repository that hides OrmManager and Jdbc access behind a simple API.
 * Use by creating a subclass or a bean that delegates to this class.
 */
public class GenericRepository<T, ID> implements LjfRepository<T, ID>, RepositoryOperations<T, ID> {

    protected final Class<T> entityClass;
    protected final OrmOperations ormManager;
    protected final EntityMetadata<T> metadata;
    protected final JdbcTemplate jdbcTemplate;

    public GenericRepository(Class<T> entityClass, OrmOperations ormManager) {
        this(entityClass, ormManager, null);
    }

    public GenericRepository(Class<T> entityClass, OrmOperations ormManager, JdbcTemplate jdbcTemplate) {
        this.entityClass = Objects.requireNonNull(entityClass);
        this.ormManager = Objects.requireNonNull(ormManager);
        this.jdbcTemplate = jdbcTemplate;
        this.metadata = EntityRegistry.getMetadata(entityClass);
    }

    // Simple CRUD wrappers that hide ormManager
    public void save(T entity) {
        try {
            Object id = metadata.idGetter().invoke(entity);
            if (id == null) throw new IllegalArgumentException("Entity must have ID (UUIDv7) before save().");
            ormManager.save(entity);
        } catch (Throwable e) {
            throw new IllegalStateException("Failed to save entity", e);
        }
    }

    public void update(T entity) {
        ormManager.update(entity);
    }

    public void delete(T entity) {
        ormManager.delete(entity);
    }

    public Optional<T> findById(Object id) {
        return ormManager.findById(entityClass, id);
    }

    public List<T> findAll(FilterBuilder filter) {
        return ormManager.findAll(entityClass, filter);
    }

    @Override
    public List<T> findAll() {
        return findAll(new FilterBuilder());
    }

    public Slice<T> findAll(FilterBuilder filter, Pageable pageable) {
        return ormManager.findAll(entityClass, filter, pageable);
    }

    @Transactional
    public void saveAll(List<T> entities, int chunkSize) {
        if (entities == null || entities.isEmpty()) return;
        if (jdbcTemplate == null) {
            for (T e : entities) save(e);
            return;
        }

        final String sql = metadata.insertSql();
        List<Object[]> batch = new ArrayList<>(Math.min(chunkSize, entities.size()));

        for (T e : entities) {
            try {
                Object id = metadata.idGetter().invoke(e);
                if (id == null) throw new IllegalArgumentException("Entity ID is null; pre-generate UUIDv7 before batch save.");
            } catch (Throwable ex) {
                throw new IllegalStateException("Failed to access entity id", ex);
            }
            List<Object> params = EntityPersister.insertValues(e, metadata);
            batch.add(params.toArray());

            if (batch.size() >= chunkSize) {
                executeJdbcBatch(sql, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) executeJdbcBatch(sql, batch);
    }

    public void saveAll(List<T> entities) {
        saveAll(entities, 500);
    }

    private void executeJdbcBatch(String sql, List<Object[]> batch) {
        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Object[] params = batch.get(i);
                for (int j = 0; j < params.length; j++) {
                    ps.setObject(j + 1, params[j]);
                }
            }

            @Override
            public int getBatchSize() {
                return batch.size();
            }
        });
    }

    // ---------------------------------------------------------------------
    // Convenience methods to fetch a single column / projection value
    // ---------------------------------------------------------------------

    private String buildSelectColumnSql(String columnName) {
        return SqlConstants.SELECT + columnName + SqlConstants.FROM + metadata.tableName();
    }

    private StringBuilder appendSoftDeleteAndFilter(StringBuilder sql, FilterBuilder filter) {
        boolean hasWhere = false;
        if (!filter.isIgnoreSoftDelete()) {
            if (metadata.hasActive()) {
                sql.append(SqlConstants.WHERE).append(metadata.activeColumn()).append(SqlConstants.EQUAL_TRUE);
                hasWhere = true;
            } else if (metadata.hasDeletedAt()) {
                sql.append(SqlConstants.WHERE).append(metadata.deletedAtColumn()).append(SqlConstants.IS_NULL);
                hasWhere = true;
            }
        }
        final String where = filter.getWhereClause();
        if (where != null && !where.isBlank()) {
            sql.append(hasWhere ? SqlConstants.AND : SqlConstants.WHERE).append(where);
        }
        return sql;
    }

    /**
     * Returns a list with the values of a single column (e.g. "name") mapped to the target type.
     */
    public <C> List<C> findColumn(String columnName, Class<C> type, FilterBuilder filter) {
        StringBuilder sql = new StringBuilder(buildSelectColumnSql(columnName));
        sql = appendSoftDeleteAndFilter(sql, filter);

        List<Object> params = filter.getParameters();

        return ormManager.client().sql(sql.toString()).params(params)
                .query((rs, rn) -> rs.getObject(columnName, type)).list();
    }

    /**
     * Convenience overload without filter (returns all values for the column).
     */
    public <C> List<C> findColumn(String columnName, Class<C> type) {
        return findColumn(columnName, type, new FilterBuilder());
    }

    /**
     * Returns a single optional value for the given column (first row) or empty if none.
     */
    public <C> Optional<C> findColumnOne(String columnName, Class<C> type, FilterBuilder filter) {
        List<C> list = findColumn(columnName, type, filter);
        return list.isEmpty() ? Optional.empty() : Optional.ofNullable(list.get(0));
    }

    public <C> Optional<C> findColumnOne(String columnName, Class<C> type) {
        return findColumnOne(columnName, type, new FilterBuilder());
    }
}
