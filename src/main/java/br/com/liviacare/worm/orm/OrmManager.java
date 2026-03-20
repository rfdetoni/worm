package br.com.liviacare.worm.orm;

import br.com.liviacare.worm.api.iBaseEntity;
import br.com.liviacare.worm.config.WormProperties;
import br.com.liviacare.worm.orm.dialect.SqlDialect;
import br.com.liviacare.worm.orm.exception.OrmOperationException;
import br.com.liviacare.worm.orm.mapping.*;
import br.com.liviacare.worm.orm.registry.EntityMetadata;
import br.com.liviacare.worm.orm.registry.EntityRegistry;
import br.com.liviacare.worm.orm.sql.OrmLogger;
import br.com.liviacare.worm.orm.sql.QueryBuilder;
import br.com.liviacare.worm.orm.sql.SqlConstants;
import br.com.liviacare.worm.orm.sql.SqlExecutor;
import br.com.liviacare.worm.orm.tracking.EntitySnapshot;
import br.com.liviacare.worm.query.FilterBuilder;
import br.com.liviacare.worm.query.Page;
import br.com.liviacare.worm.query.Pageable;
import br.com.liviacare.worm.query.Slice;
import br.com.liviacare.worm.spi.ModuleContextProvider;
import br.com.liviacare.worm.util.AliasUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Core ORM manager providing high-level database operations.
 * This class is responsible for persisting, updating, deleting, and finding entities.
 * It delegates SQL query construction to {@link QueryBuilder} and logging to {@link OrmLogger},
 * adhering to the Single Responsibility Principle.
 */
public class OrmManager implements OrmOperations {

    private static final Logger log = LoggerFactory.getLogger(OrmManager.class);

    private final SqlExecutor executor;
    private final OrmLogger ormLogger;
    private final SqlDialect dialect;
    private final int batchSize;
    private final boolean saveTryUpdateFirst;
    private final WormProperties.InsertStrategy insertStrategy;
    private final TransactionTemplate txTemplate;
    private final PostgresBulkWriter bulkWriter;
    private final java.util.concurrent.ConcurrentMap<String, String> partialUpdateSqlCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Object, EntitySnapshot> trackedSnapshots = Collections.synchronizedMap(new WeakHashMap<>());

    public OrmManager(JdbcClient jdbcClient, WormProperties properties, SqlDialect dialect) {
        this(jdbcClient, properties, dialect, null, null);
    }

    public OrmManager(JdbcClient jdbcClient, WormProperties properties, SqlDialect dialect, DataSource dataSource) {
        this(jdbcClient, properties, dialect, dataSource, null);
    }

    public OrmManager(JdbcClient jdbcClient, WormProperties properties, SqlDialect dialect,
                      DataSource dataSource, PlatformTransactionManager txManager) {
        this.executor = new SqlExecutor(jdbcClient, dataSource);
        // Use the entity's package logger as an external logger
        Logger entityLogger = LoggerFactory.getLogger("app.orm.sql"); // or use the desired package
        this.ormLogger = new OrmLogger(log, entityLogger);
        this.dialect = dialect;
        this.batchSize = properties != null ? properties.getBatchSize() : 500;
        this.saveTryUpdateFirst = properties == null || properties.isSaveTryUpdateFirst();
        this.insertStrategy = properties != null ? properties.getInsertStrategy() : WormProperties.InsertStrategy.UPSERT;
        DataSource resolvedDataSource = dataSource != null ? dataSource : this.executor.dataSourceOrNull();
        boolean isPostgres = dialect != null
                && dialect.getClass().getSimpleName().toLowerCase().contains("postgres");
        int copyThreshold = properties != null ? properties.getBulkCopyThreshold() : PostgresBulkWriter.DEFAULT_COPY_THRESHOLD;
        int unnestThreshold = properties != null ? properties.getBulkUnnestThreshold() : PostgresBulkWriter.DEFAULT_UNNEST_THRESHOLD;
        this.bulkWriter = isPostgres && resolvedDataSource != null
                ? new PostgresBulkWriter(resolvedDataSource, copyThreshold, unnestThreshold)
                : null;

        boolean txEnabled = (properties == null || properties.isTransactionEnabled()) && txManager != null;
        if (txEnabled) {
            TransactionTemplate tt = new TransactionTemplate(txManager);
            tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
            this.txTemplate = tt;
            log.info("[WORM] TransactionTemplate ativo — single-row writes protegidos de autoCommit");
        } else {
            this.txTemplate = null;
            log.warn("[WORM] TransactionTemplate NULL — single-row writes em autoCommit (performance degradada)");
        }
    }

    // Backwards-compatible ctor if someone uses it
    public OrmManager(JdbcClient jdbcClient) {
        this(jdbcClient, null, null, null, null);
    }

    public JdbcClient client() {
        return executor.client();
    }

    private <T> EntityMetadata<T> getRequiredMetadata(Class<T> clazz) {
        EntityMetadata<T> metadata = EntityRegistry.getMetadata(clazz);
        if (metadata == null) {
            throw new IllegalArgumentException("Class " + clazz.getName() + " is not a registered entity (missing @DbTable annotation)");
        }
        return metadata;
    }

    // <editor-fold desc="Write API">

    public <T> void save(T entity) {
        final EntityMetadata<T> metadata = getRequiredMetadata((Class<T>) entity.getClass());
        withModuleVoid(metadata, () -> {
            final Object id = readId(entity, metadata);
            final boolean fast = FastPathDecisionCache.canUseFastPath(metadata.entityClass(), metadata);

            // ── Path UPSERT: 1 único round-trip independente de ser novo ou existente ──
            if (insertStrategy == WormProperties.InsertStrategy.UPSERT && id != null && !metadata.hasVersion()) {
                execWrite(() -> {
                    if (entity instanceof iBaseEntity base) base.created();
                    validateIdIsPresent(entity, metadata, "save");
                    final String upsertSql = metadata.upsertSql();
                    final String sql = (upsertSql != null && !upsertSql.isBlank()) ? upsertSql : metadata.insertSql();
                    final List<Object> params = fast
                            ? EntityPersisterFastPath.insertValuesFast(entity, metadata)
                            : EntityPersister.insertValues(entity, metadata);
                    if (ormLogger.isDebugEnabled()) {
                        ormLogger.logAndExecute(SqlConstants.OP_INSERT, sql, params,
                                () -> executor.client().sql(sql).params(params).update());
                    } else {
                        executor.client().sql(sql).params(params).update();
                    }
                });
                attachSnapshot(entity, metadata);
                return;
            }

            if (id != null && !metadata.hasVersion()) {
                final Object capturedId = id;
                if (saveTryUpdateFirst && insertStrategy != WormProperties.InsertStrategy.INSERT_ONLY) {
                    execWrite(() -> {
                        if (entity instanceof iBaseEntity base) {
                            base.updated();
                        }
                        final String updateSql = metadata.updateSql();
                        final List<Object> updateParams = fast
                                ? EntityPersisterFastPath.updateValuesFast(entity, metadata, capturedId)
                                : EntityPersister.updateValues(entity, metadata, capturedId);
                        int rows;
                        if (ormLogger.isDebugEnabled()) {
                            rows = ormLogger.logAndExecute(SqlConstants.OP_UPDATE, updateSql, updateParams,
                                    () -> executor.client().sql(updateSql).params(updateParams).update());
                        } else {
                            rows = executor.client().sql(updateSql).params(updateParams).update();
                        }
                        if (rows > 0) {
                            return;
                        }
                        if (entity instanceof iBaseEntity base) {
                            base.created();
                        }
                        validateIdIsPresent(entity, metadata, "save");
                        final String insertSql = metadata.insertSql();
                        final List<Object> insertParams = fast
                                ? EntityPersisterFastPath.insertValuesFast(entity, metadata)
                                : EntityPersister.insertValues(entity, metadata);
                        if (ormLogger.isDebugEnabled()) {
                            ormLogger.logAndExecute(SqlConstants.OP_INSERT, insertSql, insertParams,
                                    () -> executor.client().sql(insertSql).params(insertParams).update());
                        } else {
                            executor.client().sql(insertSql).params(insertParams).update();
                        }
                    });
                } else {
                    attachSnapshot(entity, metadata);
                    execWrite(() -> {
                        if (entity instanceof iBaseEntity base) {
                            base.created();
                        }
                        validateIdIsPresent(entity, metadata, "save");
                        final String insertSql = metadata.insertSql();
                        final List<Object> insertParams = fast
                                ? EntityPersisterFastPath.insertValuesFast(entity, metadata)
                                : EntityPersister.insertValues(entity, metadata);

                        try {
                            if (ormLogger.isDebugEnabled()) {
                                ormLogger.logAndExecute(SqlConstants.OP_INSERT, insertSql, insertParams,
                                        () -> executor.client().sql(insertSql).params(insertParams).update());
                            } else {
                                executor.client().sql(insertSql).params(insertParams).update();
                            }
                        } catch (Throwable t) {
                            if (!isDuplicateKey(t)) {
                                throw t instanceof RuntimeException re ? re : new RuntimeException(t);
                            }
                            if (entity instanceof iBaseEntity base) {
                                base.updated();
                            }
                            final String updateSql = metadata.updateSql();
                            final List<Object> updateParams = fast
                                    ? EntityPersisterFastPath.updateValuesFast(entity, metadata, capturedId)
                                    : EntityPersister.updateValues(entity, metadata, capturedId);
                            if (ormLogger.isDebugEnabled()) {
                                ormLogger.logAndExecute(SqlConstants.OP_UPDATE, updateSql, updateParams,
                                        () -> executor.client().sql(updateSql).params(updateParams).update());
                            } else {
                                executor.client().sql(updateSql).params(updateParams).update();
                            }
                        }
                    });
                    attachSnapshot(entity, metadata);
                }
                return;
            }

            execWrite(() -> {
                if (entity instanceof iBaseEntity base) {
                    base.created();
                }
                validateIdIsPresent(entity, metadata, "save");
                final String sql = metadata.insertSql();
                final List<Object> params = fast
                        ? EntityPersisterFastPath.insertValuesFast(entity, metadata)
                        : EntityPersister.insertValues(entity, metadata);
                if (ormLogger.isDebugEnabled()) {
                    ormLogger.logAndExecute(SqlConstants.OP_INSERT, sql, params,
                            () -> executor.client().sql(sql).params(params).update());
                } else {
                    executor.client().sql(sql).params(params).update();
                }
            });
            attachSnapshot(entity, metadata);
        });
    }

    public <T> int[] saveAll(List<T> entities) {
        if (entities == null || entities.isEmpty()) return new int[0];
        return saveAllBatch(entities);
    }

    public <T> void update(T entity) {
        final EntityMetadata<T> metadata = getRequiredMetadata((Class<T>) entity.getClass());
        withModuleVoid(metadata, () -> {
            if (metadata.isTracked()) {
                EntitySnapshot snapshot = getSnapshot(entity);
                if (snapshot != null) {
                    List<String> dirtyColumns = normalizeDirtyColumns(snapshot.dirtyUpdatableColumns(entity, metadata), metadata);
                    if (dirtyColumns.isEmpty()) {
                        return;
                    }
                    updatePartial(entity, dirtyColumns);
                    return;
                }
            }

            doFullUpdate(entity, metadata);
        });
    }

    @Override
    public <T> void updatePartial(T entity, List<String> dirtyColumns) {
        final EntityMetadata<T> metadata = getRequiredMetadata((Class<T>) entity.getClass());
        withModuleVoid(metadata, () -> {
            final Object id = validateIdIsPresent(entity, metadata, "updatePartial");
            final List<String> normalizedDirty = normalizeDirtyColumns(dirtyColumns, metadata);
            if (normalizedDirty.isEmpty()) {
                return;
            }

            if (entity instanceof iBaseEntity base) {
                base.updated();
            }

            final String sql = buildPartialUpdateSql(metadata, normalizedDirty);
            final List<Object> params = EntityPersister.updateValuesForColumns(entity, metadata, id, normalizedDirty);

            int rows = execWrite(() -> {
                if (ormLogger.isDebugEnabled()) {
                    return ormLogger.logAndExecute(SqlConstants.OP_UPDATE, sql, params,
                            () -> executor.client().sql(sql).params(params).update());
                }
                return executor.client().sql(sql).params(params).update();
            });

            assertOptimisticLock(metadata, entity, id, rows);
            attachSnapshot(entity, metadata);
        });
    }

    public <T> int[] updateAll(List<T> entities) {
        if (entities == null || entities.isEmpty()) return new int[0];
        return updateAllBatch(entities);
    }

    public <T> void delete(T entity) {
        final EntityMetadata<T> metadata = getRequiredMetadata((Class<T>) entity.getClass());
        withModuleVoid(metadata, () -> {
            execWrite(() -> {
                try {
                    final Object id = metadata.idGetter().invoke(entity);
                    if (metadata.softDeleteSql() != null) {
                        if (entity instanceof iBaseEntity base) {
                            base.deleted();
                        }
                        softDelete(metadata, id);
                    } else {
                        hardDelete(metadata, id);
                    }
                } catch (OrmOperationException e) {
                    throw e;
                } catch (Throwable e) {
                    throw new OrmOperationException("Failed to delete entity: " + entity, e);
                }
            });
            clearSnapshot(entity, metadata);
        });
    }

    public <T, I> void deleteById(Class<T> clazz, I id) {
        final EntityMetadata<T> metadata = getRequiredMetadata(clazz);
        withModuleVoid(metadata, () -> execWrite(() -> {
            if (metadata.softDeleteSql() != null) {
                softDelete(metadata, id);
            } else {
                hardDelete(metadata, id);
            }
        }));
    }

    public <T> int[] deleteAll(List<T> entities) {
        if (entities == null || entities.isEmpty()) return new int[0];
        return deleteAllBatch(entities);
    }

    public <T> int[] upsertAll(List<T> entities) {
        if (entities == null || entities.isEmpty()) return new int[0];
        return upsertAllBatch(entities);
    }

    // </editor-fold>

    // <editor-fold desc="Read API">

    public <T, I> Optional<T> findById(Class<T> clazz, I id) {
        final EntityMetadata<T> metadata = getRequiredMetadata(clazz);
        return withModule(metadata, () -> {
            // Check if entity has joins - if so, use default camelCase entity alias for consistency
            boolean hasJoins = metadata.joinInfos() != null && metadata.joinInfos().length > 0;

            if (hasJoins) {
                return findById(clazz, id, AliasUtils.defaultMainAlias(metadata.entityClass()));
            }

            final String sql = metadata.selectSql() + SqlConstants.WHERE + metadata.idColumnName() + " = ?";
            final List<Object> params = List.of(id);

            Optional<T> result = ormLogger.logAndExecute(SqlConstants.OP_SELECT_BY_ID, sql, params,
                    () -> executor.timeAndRecord(SqlConstants.OP_SELECT_BY_ID, metadata.entityClass().getSimpleName(),
                            () -> {
                                if (metadata.hasCollectionJoins()) {
                                    List<T> rows = executor.client().sql(sql).param(id)
                                            .query((rs, _) -> EntityMapper.mapRow(rs, metadata))
                                            .list();
                                    List<T> merged = EntityMapper.mergeCollectionJoins(rows, metadata);
                                    return merged.isEmpty() ? Optional.empty() : Optional.of(merged.get(0));
                                }
                                return executor.client().sql(sql).param(id)
                                        .query((rs, _) -> EntityMapper.mapRow(rs, metadata))
                                        .optional();
                            })
            );
            result.ifPresent(entity -> attachSnapshot(entity, metadata));
            return result;
        });
    }

    public <T, I> Optional<T> findById(Class<T> clazz, I id, String mainAlias) {
        final EntityMetadata<T> metadata = getRequiredMetadata(clazz);
        return withModule(metadata, () -> {
            String sql = metadata.selectSql();
            if (mainAlias != null && !mainAlias.isBlank()) {
                sql = normalizeMainTableAlias(sql, mainAlias, metadata);
            }
            sql += SqlConstants.WHERE + (mainAlias != null && !mainAlias.isBlank() ? mainAlias + "." : "") + metadata.idColumnName() + " = ?";
            final String finalSql = sql;
            final List<Object> params = List.of(id);

            Optional<T> result = ormLogger.logAndExecute(SqlConstants.OP_SELECT_BY_ID, finalSql, params,
                    () -> executor.timeAndRecord(SqlConstants.OP_SELECT_BY_ID, metadata.entityClass().getSimpleName(),
                            () -> {
                                if (metadata.hasCollectionJoins()) {
                                    List<T> rows = executor.client().sql(finalSql).param(id)
                                            .query((rs, _) -> EntityMapper.mapRow(rs, metadata))
                                            .list();
                                    List<T> merged = EntityMapper.mergeCollectionJoins(rows, metadata);
                                    return merged.isEmpty() ? Optional.empty() : Optional.of(merged.get(0));
                                }
                                return executor.client().sql(finalSql).param(id)
                                        .query((rs, _) -> EntityMapper.mapRow(rs, metadata))
                                        .optional();
                            })
            );
            result.ifPresent(entity -> attachSnapshot(entity, metadata));
            return result;
        });
    }

    public <T> Optional<T> findOne(Class<T> clazz, FilterBuilder filter) {
        final EntityMetadata<T> metadata = getRequiredMetadata(clazz);
        return withModule(metadata, () -> {
            final QueryBuilder<T> queryBuilder = new QueryBuilder<>(metadata, filter, dialect);
            final String baseSql = queryBuilder.buildSelectSql(null, false);
            final String sql = (dialect != null) ? dialect.applyPagination(baseSql, 1, 0) : baseSql;
            final List<Object> params = queryBuilder.getParameters();

            Optional<T> result = ormLogger.logAndExecute(SqlConstants.OP_SELECT, sql, params,
                    () -> executor.timeAndRecord(SqlConstants.OP_SELECT, metadata.entityClass().getSimpleName(),
                            () -> {
                                if (metadata.hasCollectionJoins()) {
                                    List<T> rows = executor.client().sql(sql).params(params)
                                            .query((rs, _) -> EntityMapper.mapRow(rs, metadata))
                                            .list();
                                    List<T> merged = EntityMapper.mergeCollectionJoins(rows, metadata);
                                    return merged.isEmpty() ? Optional.empty() : Optional.of(merged.get(0));
                                }
                                return executor.client().sql(sql).params(params)
                                        .query((rs, _) -> EntityMapper.mapRow(rs, metadata))
                                        .optional();
                            }));
            result.ifPresent(entity -> attachSnapshot(entity, metadata));
            return result;
        });
    }

    public <T> List<T> findAll(Class<T> clazz, FilterBuilder filter) {
        return findAll(clazz, filter, (Pageable) null).content();
    }

    public <T> Slice<T> findAll(Class<T> clazz, FilterBuilder filter, Pageable pageable) {
        final EntityMetadata<T> metadata = getRequiredMetadata(clazz);
        return withModule(metadata, () -> {
            final QueryBuilder<T> queryBuilder = new QueryBuilder<>(metadata, filter, dialect);

            final String sql = queryBuilder.buildSelectSql(pageable, true);
            final List<Object> params = queryBuilder.getParameters();

            List<T> results = ormLogger.logAndExecute(SqlConstants.OP_SELECT, sql, params,
                    () -> executor.timeAndRecord(SqlConstants.OP_SELECT, metadata.entityClass().getSimpleName(),
                            () -> executor.client().sql(sql).params(params)
                                    .query((rs, _) -> EntityMapper.mapRow(rs, metadata))
                                    .list()));

            // Merge collection-join rows (one-to-many) before applying pagination trimming
            if (metadata.hasCollectionJoins()) {
                results = EntityMapper.mergeCollectionJoins(results, metadata);
            }

            boolean hasNext = false;
            if (pageable != null) {
                final int pageSize = pageable.pageSize();
                if (results.size() > pageSize) {
                    results = new ArrayList<>(results.subList(0, pageSize));
                    hasNext = true;
                }
            }

            attachSnapshots(results, metadata);

            return new Slice<>(results, pageable, hasNext);
        });
    }

    public <T> Page<T> findPage(Class<T> clazz, FilterBuilder filter, Pageable pageable) {
        final EntityMetadata<T> metadata = getRequiredMetadata(clazz);
        return withModule(metadata, () -> {
            Slice<T> slice = findAll(clazz, filter, pageable);
            if (pageable != null && pageable.pageNumber() == 0 && !slice.hasNext()) {
                long total = slice.content().size();
                int totalPages = total > 0 ? 1 : 0;
                return new Page<>(slice.content(), pageable, false, total, totalPages);
            }
            final long total = count(clazz, filter);
            int totalPages = (pageable != null && pageable.pageSize() > 0)
                    ? (int) ((total + pageable.pageSize() - 1) / pageable.pageSize())
                    : (total > 0 ? 1 : 0);
            return new Page<>(slice.content(), pageable, slice.hasNext(), total, totalPages);
        });
    }

    // </editor-fold>

    // <editor-fold desc="Projections and Aggregates API">

    public <T> boolean exists(Class<T> clazz, FilterBuilder filter) {
        final EntityMetadata<T> metadata = getRequiredMetadata(clazz);
        return withModule(metadata, () -> {
            final QueryBuilder<T> queryBuilder = new QueryBuilder<>(metadata, filter, dialect);
            final String sql = queryBuilder.buildExistsSql();
            final List<Object> params = queryBuilder.getParameters();

            return ormLogger.logAndExecute("EXISTS", sql, params,
                    () -> executor.client().sql(sql).params(params)
                            .query((rs, rn) -> rs.getObject(1))
                            .optional().isPresent());
        });
    }

    public <T, I> boolean existsById(Class<T> clazz, I id) {
        final EntityMetadata<T> metadata = getRequiredMetadata(clazz);
        return withModule(metadata, () -> {
            final String sql = "SELECT 1 FROM " + metadata.tableName() + " WHERE " + metadata.idColumnName() + " = ? LIMIT 1";
            return executor.client().sql(sql).param(id).query((rs, rn) -> rs.getObject(1)).optional().isPresent();
        });
    }

    public <T> long count(Class<T> clazz) {
        final EntityMetadata<T> metadata = getRequiredMetadata(clazz);
        return withModule(metadata, () -> {
            final String sql = metadata.countSql();
            return ormLogger.logAndExecute("COUNT", sql, List.of(),
                    () -> executor.client().sql(sql)
                            .query((rs, _) -> rs.getLong(1))
                            .optional().orElse(0L));
        });
    }

    public <T> long count(Class<T> clazz, FilterBuilder filter) {
        final EntityMetadata<T> metadata = getRequiredMetadata(clazz);
        return withModule(metadata, () -> {
            boolean hasJoins = metadata.joinInfos() != null && metadata.joinInfos().length > 0;

            if (!hasJoins && (filter == null || filter.getWhereClause().isEmpty())) {
                final String sql = metadata.countSql();
                return ormLogger.logAndExecute("COUNT", sql, List.of(),
                        () -> executor.client().sql(sql)
                                .query((rs, _) -> rs.getLong(1))
                                .optional().orElse(0L));
            }

            final QueryBuilder<T> queryBuilder = new QueryBuilder<>(metadata, filter, dialect);
            final String sql = queryBuilder.buildCountSql();
            final List<Object> params = queryBuilder.getParameters();

            return ormLogger.logAndExecute("COUNT", sql, params,
                    () -> executor.client().sql(sql).params(params)
                            .query((rs, _) -> rs.getLong(1))
                            .optional().orElse(0L));
        });
    }

    public <T, C> List<C> findColumn(Class<T> clazz, String columnName, Class<C> type, FilterBuilder filter) {
        final EntityMetadata<T> metadata = getRequiredMetadata(clazz);
        return withModule(metadata, () -> {
            final QueryBuilder<T> qb = new QueryBuilder<>(metadata, filter, dialect);
            final String fromWhere = qb.buildFromJoinsAndWhere();
            final String sql = "SELECT " + columnName + fromWhere;
            final List<Object> params = qb.getParameters();

            return ormLogger.logAndExecute("SELECT_COLUMN", sql, params,
                    () -> executor.timeAndRecord("SELECT_COLUMN", metadata.entityClass().getSimpleName(),
                            () -> executor.client().sql(sql).params(params)
                                    .query((rs, rn) -> rs.getObject(1, type)).list()));
        });
    }

    public <T, C> Optional<C> findColumnOne(Class<T> clazz, String columnName, Class<C> type, FilterBuilder filter) {
        List<C> list = findColumn(clazz, columnName, type, filter);
        return list.isEmpty() ? Optional.empty() : Optional.ofNullable(list.get(0));
    }

    public <T, N extends Number> Optional<N> sum(Class<T> clazz, String column, Class<N> type, FilterBuilder filter) {
        return aggregateRaw(clazz, "SUM(" + column + ")", filter).flatMap(v -> convertNumber(v, type));
    }

    public <T, N extends Number> Optional<N> min(Class<T> clazz, String column, Class<N> type, FilterBuilder filter) {
        return aggregateRaw(clazz, "MIN(" + column + ")", filter).flatMap(v -> convertNumber(v, type));
    }

    public <T, N extends Number> Optional<N> max(Class<T> clazz, String column, Class<N> type, FilterBuilder filter) {
        return aggregateRaw(clazz, "MAX(" + column + ")", filter).flatMap(v -> convertNumber(v, type));
    }

    public <T> Optional<Double> avg(Class<T> clazz, String column, FilterBuilder filter) {
        return aggregateRaw(clazz, "AVG(" + column + ")", filter).flatMap(v -> convertNumber(v, Double.class));
    }

    // </editor-fold>

    // <editor-fold desc="Raw and JSON Query API">

    public <T, R> List<R> queryList(Class<T> entityClass, String baseSql, Class<R> type, FilterBuilder filter) {
        final EntityMetadata<T> md = getRequiredMetadata(entityClass);
        return withModule(md, () -> {
            final String sql = buildCustomQuerySql(entityClass, baseSql, filter);
            final List<Object> params = filter.getParameters();
            return ormLogger.logAndExecute("RAW_QUERY_LIST", sql, params,
                    () -> executor.timeAndRecord("RAW_QUERY_LIST", md.entityClass().getSimpleName(),
                            () -> executor.client().sql(sql).params(params).query((rs, rn) -> rs.getObject(1, type)).list()));
        });
    }

    public <T, R> Optional<R> queryOne(Class<T> entityClass, String baseSql, Class<R> type, FilterBuilder filter) {
        List<R> list = queryList(entityClass, baseSql, type, filter);
        return list.isEmpty() ? Optional.empty() : Optional.ofNullable(list.get(0));
    }

    public <T> Optional<String> jsonPathQueryFirst(Class<T> clazz, String column, String jsonPath, FilterBuilder filter) {
        return jsonPathQueryFirstWithVars(clazz, column, jsonPath, null, filter);
    }

    public <T> Optional<String> jsonPathQueryFirstWithVars(Class<T> clazz, String column, String jsonPath, Object varsJson, FilterBuilder filter) {
        final EntityMetadata<T> metadata = getRequiredMetadata(clazz);
        return withModule(metadata, () -> {
            String functionCall = "jsonb_path_query_first(" + column + ", ?::jsonpath" + (varsJson != null ? ", ?::jsonb" : "") + ")::text";
            String baseSql = "SELECT " + functionCall;

            final String sql = buildCustomQuerySql(clazz, baseSql, filter);
            final List<Object> params = buildJsonPathParams(jsonPath, varsJson, filter);

            return ormLogger.logAndExecute("JSON_PATH_FIRST", sql, params,
                    () -> executor.client().sql(sql).params(params)
                            .query((rs, rn) -> rs.getString(1)).optional());
        });
    }

    public <T> Optional<String> jsonPathQueryArray(Class<T> clazz, String column, String jsonPath, FilterBuilder filter) {
        final EntityMetadata<T> metadata = getRequiredMetadata(clazz);
        return withModule(metadata, () -> {
            String functionCall = "jsonb_path_query_array(" + column + ", ?::jsonpath)::text";
            String baseSql = "SELECT " + functionCall;

            final String sql = buildCustomQuerySql(clazz, baseSql, filter);
            final List<Object> params = buildJsonPathParams(jsonPath, null, filter);

            return ormLogger.logAndExecute("JSON_PATH_ARRAY", sql, params,
                    () -> executor.client().sql(sql).params(params)
                            .query((rs, rn) -> rs.getString(1)).optional());
        });
    }

    // </editor-fold>

    // <editor-fold desc="Private Helpers">

    /**
     * Executes the given supplier with the entity's module pushed onto ModuleContext,
     * so that ModuleRoutingDataSource routes to the correct DataSource for this entity.
     * If the metadata has no module, or a module is already active, delegates directly.
     */
    private <R> R withModule(EntityMetadata<?> metadata, java.util.function.Supplier<R> action) {
        if (metadata == null) return action.get();
        String module = metadata.module();
        if (module == null || module.isBlank()) return action.get();
        
        if (ModuleContextProvider.get().getCurrentModule() != null) {
            return action.get();
        }

        return ModuleContextProvider.get().withModule(module, action);
    }

    private void withModuleVoid(EntityMetadata<?> metadata, Runnable action) {
        if (metadata == null) {
            action.run();
            return;
        }
        String module = metadata.module();
        if (module == null || module.isBlank()) {
            action.run();
            return;
        }
        if (ModuleContextProvider.get().getCurrentModule() != null) {
            action.run();
            return;
        }
        ModuleContextProvider.get().withModuleVoid(module, action);
    }

    private void execWrite(Runnable action) {
        if (txTemplate != null) {
            txTemplate.executeWithoutResult(status -> action.run());
        } else {
            action.run();
        }
    }

    private <R> R execWrite(java.util.function.Supplier<R> action) {
        return txTemplate != null ? txTemplate.execute(status -> action.get()) : action.get();
    }

    /** Reads the entity ID without throwing — returns null on failure. */
    private <T> Object readId(T entity, EntityMetadata<T> metadata) {
        try { return metadata.idGetter().invoke(entity); } catch (Throwable ignored) { return null; }
    }

    @SuppressWarnings("unchecked")
    private <T> EntityMetadata<T> getMetadata(T entity) {
        return getRequiredMetadata((Class<T>) entity.getClass());
    }

    private <T> Object validateIdIsPresent(T entity, EntityMetadata<T> metadata, String operation) {
        try {
            final Object id = metadata.idGetter().invoke(entity);
            if (id == null) {
                throw new IllegalArgumentException(
                        String.format("Entity %s must have a non-null ID for the '%s' operation.",
                                metadata.entityClass().getSimpleName(), operation)
                );
            }
            return id;
        } catch (Throwable e) {
            if (e instanceof IllegalArgumentException) throw (IllegalArgumentException) e;
            throw new OrmOperationException("Failed to access ID on entity: " + entity, e);
        }
    }

    private <T> void doFullUpdate(T entity, EntityMetadata<T> metadata) {
        final Object id = validateIdIsPresent(entity, metadata, "update");

        if (entity instanceof iBaseEntity base) {
            base.updated();
        }

        final boolean fast = FastPathDecisionCache.canUseFastPath(metadata.entityClass(), metadata);
        final String sql = metadata.updateSql();
        final List<Object> params = fast
                ? EntityPersisterFastPath.updateValuesFast(entity, metadata, id)
                : EntityPersister.updateValues(entity, metadata, id);

        int rows = execWrite(() -> {
            if (ormLogger.isDebugEnabled()) {
                return ormLogger.logAndExecute(SqlConstants.OP_UPDATE, sql, params,
                        () -> executor.client().sql(sql).params(params).update());
            }
            return executor.client().sql(sql).params(params).update();
        });

        assertOptimisticLock(metadata, entity, id, rows);
        attachSnapshot(entity, metadata);
    }

    private <T> void assertOptimisticLock(EntityMetadata<T> metadata, T entity, Object id, int rows) {
        if (!metadata.hasVersion() || rows != 0) {
            return;
        }

        Object version = null;
        try {
            version = metadata.versionGetter().invoke(entity);
        } catch (Throwable ignored) {
        }
        try {
            executor.incrementOptimisticLockFailure(metadata.entityClass().getSimpleName());
        } catch (Exception ignored) {
        }
        throw new br.com.liviacare.worm.orm.exception.OptimisticLockException(metadata.entityClass(), id, version);
    }

    private <T> List<String> normalizeDirtyColumns(List<String> dirtyColumns, EntityMetadata<T> metadata) {
        if (dirtyColumns == null || dirtyColumns.isEmpty()) {
            return List.of();
        }

        Set<String> requested = new LinkedHashSet<>();
        for (String column : dirtyColumns) {
            if (column != null && metadata.updatableColumns().contains(column)) {
                requested.add(column);
            }
        }
        if (requested.isEmpty()) {
            return List.of();
        }

        metadata.updatedAtColumn()
                .filter(metadata.updatableColumns()::contains)
                .ifPresent(requested::add);

        List<String> normalized = new ArrayList<>(requested.size());
        for (String column : metadata.updatableColumns()) {
            if (requested.contains(column)) {
                normalized.add(column);
            }
        }
        return normalized;
    }

    private <T> String buildPartialUpdateSql(EntityMetadata<T> metadata, List<String> dirtyColumns) {
        String key = metadata.tableName() + "|" + String.join(",", dirtyColumns) + "|" + metadata.hasVersion();
        return partialUpdateSqlCache.computeIfAbsent(key, ignored -> {
            StringJoiner set = new StringJoiner(", ");
            for (String column : dirtyColumns) {
                set.add(column + " = ?");
            }
            if (metadata.hasVersion()) {
                String versionCol = metadata.versionColumn();
                set.add(versionCol + " = " + versionCol + " + 1");
                return "UPDATE " + metadata.tableName() + " SET " + set
                        + " WHERE " + metadata.idColumnName() + " = ? AND " + versionCol + " = ?";
            }
            return "UPDATE " + metadata.tableName() + " SET " + set
                    + " WHERE " + metadata.idColumnName() + " = ?";
        });
    }

    private <T> EntitySnapshot getSnapshot(T entity) {
        if (entity == null) {
            return null;
        }
        if (entity instanceof br.com.liviacare.worm.ActiveRecord<?, ?> activeRecord) {
            return activeRecord.__wormSnapshot();
        }
        synchronized (trackedSnapshots) {
            return trackedSnapshots.get(entity);
        }
    }

    private <T> void attachSnapshot(T entity, EntityMetadata<T> metadata) {
        if (!metadata.isTracked() || entity == null) {
            return;
        }

        EntitySnapshot snapshot = EntitySnapshot.capture(entity, metadata);
        if (entity instanceof br.com.liviacare.worm.ActiveRecord<?, ?> activeRecord) {
            activeRecord.__wormSetSnapshot(snapshot);
            return;
        }
        synchronized (trackedSnapshots) {
            trackedSnapshots.put(entity, snapshot);
        }
    }

    private <T> void attachSnapshots(List<T> entities, EntityMetadata<T> metadata) {
        if (!metadata.isTracked() || entities == null || entities.isEmpty()) {
            return;
        }
        for (T entity : entities) {
            attachSnapshot(entity, metadata);
        }
    }

    private <T> void clearSnapshot(T entity, EntityMetadata<T> metadata) {
        if (!metadata.isTracked() || entity == null) {
            return;
        }
        if (entity instanceof br.com.liviacare.worm.ActiveRecord<?, ?> activeRecord) {
            activeRecord.__wormClearSnapshot();
            return;
        }
        synchronized (trackedSnapshots) {
            trackedSnapshots.remove(entity);
        }
    }

    private <T, I> void hardDelete(EntityMetadata<T> metadata, I id) {
        final String sql = metadata.deleteSql();
        final List<Object> params = List.of(id);
        if (ormLogger.isDebugEnabled()) {
            ormLogger.logAndExecute(SqlConstants.OP_DELETE, sql, params,
                    () -> executor.client().sql(sql).param(id).update());
        } else {
            executor.client().sql(sql).param(id).update();
        }
    }

    private <T, I> void softDelete(EntityMetadata<T> metadata, I id) {
        final String sql = metadata.softDeleteSql();
        final List<Object> params;
        final Runnable execution;

        // MetadataBuilder prioritizes @Active over @DeletedAt when generating softDeleteSql.
        // Keep the bind list in the same precedence to avoid placeholder mismatches.
        if (metadata.hasActive()) {
            params = List.of(id);
            execution = () -> executor.client().sql(sql).param(id).update();
        } else if (metadata.hasDeletedAt()) {
            final Instant now = Instant.now();
            params = List.of(now, id);
            execution = () -> executor.client().sql(sql).params(now, id).update();
        } else {
            params = List.of(id);
            execution = () -> executor.client().sql(sql).param(id).update();
        }

        if (ormLogger.isDebugEnabled()) {
            ormLogger.logAndExecute(SqlConstants.OP_SOFT_DELETE, sql, params, execution);
        } else {
            execution.run();
        }
    }

    private boolean isDuplicateKey(Throwable t) {
        if (t instanceof DuplicateKeyException) {
            return true;
        }
        Throwable current = t;
        while (current != null) {
            if (current instanceof SQLException sqlEx && "23505".equals(sqlEx.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private <T> Optional<Object> aggregateRaw(Class<T> clazz, String selectExpr, FilterBuilder filter) {
        final EntityMetadata<T> metadata = getRequiredMetadata(clazz);
        return withModule(metadata, () -> {
            final QueryBuilder<T> qb = new QueryBuilder<>(metadata, filter, dialect);
            final String fromWhere = qb.buildFromJoinsAndWhere();
            final String sql = "SELECT " + selectExpr + fromWhere;
            final List<Object> params = qb.getParameters();

            return ormLogger.logAndExecute(selectExpr, sql, params,
                    () -> executor.timeAndRecord(selectExpr, metadata.entityClass().getSimpleName(),
                            () -> executor.client().sql(sql).params(params)
                                    .query((rs, rn) -> rs.getObject(1)).optional()));
        });
    }

    @SuppressWarnings("unchecked")
    private static <N extends Number> Optional<N> convertNumber(Object val, Class<N> type) {
        if (val == null) return Optional.empty();
        if (type.isInstance(val)) return Optional.of((N) val);
        if (!(val instanceof Number n)) {
            return Optional.empty();
        }
        if (type == Long.class) return Optional.of((N) Long.valueOf(n.longValue()));
        if (type == Integer.class) return Optional.of((N) Integer.valueOf(n.intValue()));
        if (type == Double.class) return Optional.of((N) Double.valueOf(n.doubleValue()));
        if (type == Float.class) return Optional.of((N) Float.valueOf(n.floatValue()));
        if (type == Short.class) return Optional.of((N) Short.valueOf(n.shortValue()));
        if (type == BigDecimal.class) return Optional.of((N) new BigDecimal(n.toString()));
        return Optional.empty();
    }

    private <T> String buildCustomQuerySql(Class<T> entityClass, String baseSql, FilterBuilder filter) {
        final EntityMetadata<T> md = getRequiredMetadata(entityClass);
        final QueryBuilder<T> qb = new QueryBuilder<>(md, filter, dialect);
        String fromWhere = qb.buildFromJoinsAndWhere();
        // If baseSql already contains FROM, we should only append the WHERE part
        if (baseSql.toUpperCase().contains(" FROM ")) {
            int whereIdx = fromWhere.toUpperCase().indexOf(" WHERE ");
            if (whereIdx != -1) {
                return baseSql + fromWhere.substring(whereIdx);
            }
            return baseSql;
        }
        return baseSql + fromWhere;
    }

    private List<Object> buildJsonPathParams(String jsonPath, Object varsJson, FilterBuilder filter) {
        List<Object> allParams = new ArrayList<>();
        allParams.add(jsonPath);
        if (varsJson != null) {
            allParams.add(varsJson instanceof Enum<?> e ? e.name() : varsJson);
        }
        allParams.addAll(filter.getParameters());
        return allParams;
    }

    // </editor-fold>

    // helper: chunking for executeBatch
    private int[] executeBatchInChunks(String sql, List<Object[]> params, String entityName) {
        if (params == null || params.isEmpty()) return new int[0];
        final int size = params.size();
        final int bs = Math.max(1, this.batchSize);
        if (size <= bs) {
            return executor.executeBatch(sql, params, entityName);
        }
        List<int[]> parts = new ArrayList<>();
        for (int i = 0; i < size; i += bs) {
            int to = Math.min(i + bs, size);
            List<Object[]> sub = params.subList(i, to);
            parts.add(executor.executeBatch(sql, sub, entityName));
        }
        // concatenate results
        int total = parts.stream().mapToInt(arr -> arr.length).sum();
        int[] out = new int[total];
        int pos = 0;
        for (int[] p : parts) {
            for (int v : p) out[pos++] = v;
        }
        return out;
    }

    private int[] executeBatchInChunks(String sql, List<Object[]> params) {
        return executeBatchInChunks(sql, params, null);
    }

    public <T> int[] saveAllBatch(List<T> entities) {
        if (entities == null || entities.isEmpty()) return new int[0];
        final EntityMetadata<T> meta = getRequiredMetadata((Class<T>) entities.get(0).getClass());
        return withModule(meta, () -> {
            for (T entity : entities) {
                validateIdIsPresent(entity, meta, "saveAll");
            }
            if (bulkWriter != null) {
                int[] copyResult = bulkWriter.copyInsert(entities, meta);
                if (copyResult != null) {
                    attachSnapshots(entities, meta);
                    return copyResult;
                }
            }
            // Fallback: batchUpdate dentro de transação única
            int[] results = execWrite(() -> {
                final String sql = meta.insertSql();
                final String entityName = meta.entityClass().getSimpleName();
                final boolean fast = FastPathDecisionCache.canUseFastPath(meta.entityClass(), meta);
                final List<Object[]> params = new ArrayList<>(entities.size());
                for (T e : entities) {
                    if (e instanceof iBaseEntity base) {
                        base.created();
                    }
                    params.add(fast
                            ? EntityPersisterFastPath.insertValuesArrayFast(e, meta)
                            : EntityPersister.insertValuesArray(e, meta));
                }
                if (ormLogger.isDebugEnabled()) {
                    return ormLogger.logBatchAndExecute(SqlConstants.OP_INSERT_BATCH, sql, params,
                            () -> executeBatchInChunks(sql, params, entityName));
                }
                return executeBatchInChunks(sql, params, entityName);
            });
            attachSnapshots(entities, meta);
            return results;
        });
    }

    public <T> int[] updateAllBatch(List<T> entities) {
        if (entities == null || entities.isEmpty()) return new int[0];
        final EntityMetadata<T> meta = getRequiredMetadata((Class<T>) entities.get(0).getClass());
        return withModule(meta, () -> {
            if (bulkWriter != null) {
                int[] unnestResult = bulkWriter.unnestUpdate(entities, meta);
                if (unnestResult != null) {
                    attachSnapshots(entities, meta);
                    return unnestResult;
                }
            }
            // Fallback: batchUpdate dentro de transação única
            int[] results = execWrite(() -> {
                final String sql = meta.updateSql();
                final String entityName = meta.entityClass().getSimpleName();
                final boolean fast = FastPathDecisionCache.canUseFastPath(meta.entityClass(), meta);
                final List<Object[]> params = new ArrayList<>(entities.size());
                final List<Object> ids = new ArrayList<>(entities.size());
                final List<Object> versions = new ArrayList<>(entities.size());
                for (T e : entities) {
                    Object id = validateIdIsPresent(e, meta, "updateAll");
                    if (e instanceof iBaseEntity base) {
                        base.updated();
                    }
                    params.add(fast
                            ? EntityPersisterFastPath.updateValuesArrayFast(e, meta, id)
                            : EntityPersister.updateValuesArray(e, meta, id));
                    ids.add(id);
                    if (meta.hasVersion()) {
                        try {
                            versions.add(meta.versionGetter().invoke(e));
                        } catch (Throwable ex) {
                            versions.add(null);
                        }
                    }
                }
                int[] batchResults;
                if (ormLogger.isDebugEnabled()) {
                    batchResults = ormLogger.logBatchAndExecute(SqlConstants.OP_UPDATE_BATCH, sql, params,
                            () -> executeBatchInChunks(sql, params, entityName));
                } else {
                    batchResults = executeBatchInChunks(sql, params, entityName);
                }
                if (meta.hasVersion()) {
                    for (int i = 0; i < batchResults.length; i++) {
                        if (batchResults[i] == 0) {
                            throw new br.com.liviacare.worm.orm.exception.OptimisticLockException(meta.entityClass(), ids.get(i), versions.get(i));
                        }
                    }
                }
                return batchResults;
            });
            attachSnapshots(entities, meta);
            return results;
        });
    }

    public <T> int[] deleteAllBatch(List<T> entities) {
        if (entities == null || entities.isEmpty()) return new int[0];
        final EntityMetadata<T> meta = getRequiredMetadata((Class<T>) entities.get(0).getClass());
        return withModule(meta, () -> {
            if (bulkWriter != null) {
                int[] unnestResult = bulkWriter.unnestDelete(entities, meta);
                if (unnestResult != null) {
                    for (T entity : entities) {
                        clearSnapshot(entity, meta);
                    }
                    return unnestResult;
                }
            }
            // Fallback: batchUpdate dentro de transação única
            int[] results = execWrite(() -> {
                final String sql = meta.softDeleteSql() != null ? meta.softDeleteSql() : meta.deleteSql();
                final String entityName = meta.entityClass().getSimpleName();
                final List<Object[]> params = new ArrayList<>(entities.size());
                for (T e : entities) {
                    Object id = validateIdIsPresent(e, meta, "deleteAll");
                    if (meta.softDeleteSql() != null && e instanceof iBaseEntity base) {
                        base.deleted();
                    }
                    if (meta.softDeleteSql() != null && meta.hasDeletedAt() && !meta.hasActive()) {
                        params.add(new Object[]{Instant.now(), id});
                    } else {
                        params.add(new Object[]{id});
                    }
                }
                if (ormLogger.isDebugEnabled()) {
                    return ormLogger.logBatchAndExecute(SqlConstants.OP_DELETE_BATCH, sql, params,
                            () -> executeBatchInChunks(sql, params, entityName));
                }
                return executeBatchInChunks(sql, params, entityName);
            });
            for (T entity : entities) {
                clearSnapshot(entity, meta);
            }
            return results;
        });
    }

    public <T> int[] upsertAllBatch(List<T> entities) {
        if (entities == null || entities.isEmpty()) return new int[0];
        final EntityMetadata<T> meta = getRequiredMetadata((Class<T>) entities.get(0).getClass());
        return withModule(meta, () -> {
            final String sql = (this.dialect != null) ? this.dialect.buildUpsertSql(meta) : meta.insertSql();
            final String entityName = meta.entityClass().getSimpleName();
            final List<Object[]> params = new ArrayList<>(entities.size());
            for (T e : entities) {
                if (e instanceof iBaseEntity base) {
                    Object id = null;
                    try {
                        id = meta.idGetter().invoke(e);
                    } catch (Throwable ignored) {
                    }
                    if (id == null) {
                        base.created();
                    } else {
                        base.updated();
                    }
                }
                validateIdIsPresent(e, meta, "upsertAll");
                params.add(EntityPersister.insertValuesArray(e, meta));
            }
            int[] results;
            if (ormLogger.isDebugEnabled()) {
                results = ormLogger.logBatchAndExecute(SqlConstants.OP_UPSERT_BATCH, sql, params,
                        () -> executeBatchInChunks(sql, params, entityName));
            } else {
                results = executeBatchInChunks(sql, params, entityName);
            }
            attachSnapshots(entities, meta);
            return results;
        });
    }

    public <T, P> List<P> findAll(Class<T> entityClass, FilterBuilder filter, Class<P> projectionClass) {
        final EntityMetadata<T> metadata = getRequiredMetadata(entityClass);
        return withModule(metadata, () -> {
            final br.com.liviacare.worm.orm.registry.ProjectionMetadata proj = EntityRegistry.getProjectionMetadata(projectionClass, metadata);
            final QueryBuilder<T> qb = new QueryBuilder<>(metadata, filter, dialect);
            final String sql = qb.buildSelectSql(proj, null, true);
            final List<Object> params = qb.getParameters();

            return ormLogger.logAndExecute(SqlConstants.OP_SELECT, sql, params,
                    () -> {
                        try {
                            @SuppressWarnings("unchecked")
                            List<P> list = (List<P>) executor.client().sql(sql).params(params)
                                    .query((rs, rn) -> {
                                        try {
                                            return EntityMapper.mapToProjection(rs, proj);
                                        } catch (Throwable e) {
                                            throw new RuntimeException(e);
                                        }
                                    }).list();
                            // If projection has collection components, aggregate rows into single projection per parent
                            return aggregateProjectionRows(list, proj);
                        } catch (RuntimeException e) {
                            throw e;
                        }
                    });
        });
    }

    public <T, P> Optional<P> findOne(Class<T> entityClass, FilterBuilder filter, Class<P> projectionClass) {
        final EntityMetadata<T> metadata = getRequiredMetadata(entityClass);
        return withModule(metadata, () -> {
            final br.com.liviacare.worm.orm.registry.ProjectionMetadata proj = EntityRegistry.getProjectionMetadata(projectionClass, metadata);
            final QueryBuilder<T> qb = new QueryBuilder<>(metadata, filter, dialect);
            final String baseSql = qb.buildSelectSql(proj, null, false);
            final String sql = (dialect != null) ? dialect.applyPagination(baseSql, 1, 0) : baseSql;
            final List<Object> params = qb.getParameters();

            return ormLogger.logAndExecute(SqlConstants.OP_SELECT, sql, params,
                    () -> {
                        @SuppressWarnings("unchecked")
                        Optional<P> opt = (Optional<P>) executor.client().sql(sql).params(params)
                                .query((rs, rn) -> {
                                    try {
                                        return EntityMapper.mapToProjection(rs, proj);
                                    } catch (Throwable e) {
                                        throw new RuntimeException(e);
                                    }
                                })
                                .optional();
                        return opt;
                    });
        });
    }

    public <T, P> Optional<P> findById(Class<T> entityClass, Object id, Class<P> projectionClass) {
        final EntityMetadata<T> metadata = getRequiredMetadata(entityClass);
        return withModule(metadata, () -> {
            final br.com.liviacare.worm.orm.registry.ProjectionMetadata proj = EntityRegistry.getProjectionMetadata(projectionClass, metadata);
            final String sql = proj.selectSql() + " WHERE " + metadata.idColumnName() + " = ?";
            final List<Object> params = List.of(id);

            return ormLogger.logAndExecute(SqlConstants.OP_SELECT_BY_ID, sql, params,
                    () -> {
                        @SuppressWarnings("unchecked")
                        Optional<P> opt = (Optional<P>) executor.client().sql(sql).params(params)
                                .query((rs, rn) -> {
                                    try {
                                        return EntityMapper.mapToProjection(rs, proj);
                                    } catch (Throwable e) {
                                        throw new RuntimeException(e);
                                    }
                                })
                                .optional();
                        return opt;
                    });
        });
    }

    // Execute raw SQL and map to resultClass. If resultClass has EntityMetadata, use EntityMapper; otherwise try to map by record components or by field names.
    public <T> List<T> executeRaw(String sql, Class<T> resultClass, Object... params) {
        final List<Object> paramList = params == null ? List.of() : Arrays.asList(params);
        final EntityMetadata<T> md = EntityRegistry.getMetadata(resultClass);
        java.util.function.Supplier<List<T>> supplier = () -> executor.client().sql(sql).params(paramList)
                .query((rs, rn) -> {
                    try {
                        if (md != null) return EntityMapper.mapRow(rs, md);
                    } catch (Throwable ignored) {}
                    try {
                        return mapRowToClass(rs, resultClass);
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                }).list();

        List<T> results = withModule(md, () ->
            ormLogger.logAndExecute("RAW_EXECUTE", sql, paramList,
                    () -> executor.timeAndRecord("RAW_EXECUTE", resultClass.getSimpleName(), supplier))
        );
        if (md != null) {
            attachSnapshots(results, md);
        }
        return results;
    }

    public <T> List<T> findAllWithCte(Class<T> entityClass, FilterBuilder filterWithCte) {
        final EntityMetadata<T> metadata = getRequiredMetadata(entityClass);
        return withModule(metadata, () -> {
            final QueryBuilder<T> qb = new QueryBuilder<>(metadata, filterWithCte, dialect);
            final String sql = qb.buildSelectSql(null, true);
            final List<Object> params = qb.getParameters();
            java.util.function.Supplier<List<T>> supplier = () -> executor.client().sql(sql).params(params)
                    .query((rs, _) -> EntityMapper.mapRow(rs, metadata)).list();

            List<T> results = ormLogger.logAndExecute(SqlConstants.OP_SELECT, sql, params,
                    () -> executor.timeAndRecord(SqlConstants.OP_SELECT, metadata.entityClass().getSimpleName(), supplier));
            attachSnapshots(results, metadata);
            return results;
        });
    }

    public <T, P> List<P> findAllWithCte(Class<T> entityClass, FilterBuilder filterWithCte, Class<P> projectionClass) {
        final EntityMetadata<T> metadata = getRequiredMetadata(entityClass);
        return withModule(metadata, () -> {
            final br.com.liviacare.worm.orm.registry.ProjectionMetadata proj = EntityRegistry.getProjectionMetadata(projectionClass, metadata);
            final QueryBuilder<T> qb = new QueryBuilder<>(metadata, filterWithCte, dialect);
            final String sql = qb.buildSelectSql(proj, null, true);
            final List<Object> params = qb.getParameters();

            java.util.function.Supplier<List<P>> supplier = () -> (List<P>) executor.client().sql(sql).params(params)
                    .query((rs, rn) -> { try { return EntityMapper.mapToProjection(rs, proj); } catch (Throwable e) { throw new RuntimeException(e);} }).list();

            return ormLogger.logAndExecute(SqlConstants.OP_SELECT, sql, params,
                    () -> executor.timeAndRecord(SqlConstants.OP_SELECT, metadata.entityClass().getSimpleName(), () -> {
                        List<P> list = supplier.get();
                        return aggregateProjectionRows(list, proj);
                    }));
        });
    }

    @SuppressWarnings("unchecked")
    private <T> T mapRowToClass(java.sql.ResultSet rs, Class<T> resultClass) throws Exception {
        if (resultClass.isRecord()) {
            // try to map to record via canonical constructor: match constructor param names to column labels
            java.lang.reflect.RecordComponent[] comps = resultClass.getRecordComponents();
            Object[] args = new Object[comps.length];
            for (int i = 0; i < comps.length; i++) {
                String name = comps[i].getName();
                Object raw = rs.getObject(name);
                args[i] = raw;
            }
            java.lang.invoke.MethodHandles.Lookup lk = java.lang.invoke.MethodHandles.lookup();
            java.lang.invoke.MethodHandle ctor = java.lang.invoke.MethodHandles.privateLookupIn(resultClass, lk)
                    .findConstructor(resultClass, java.lang.invoke.MethodType.methodType(void.class, Arrays.stream(comps).map(c -> c.getType()).toArray(Class[]::new)));
            try {
                return (T) ctor.invokeWithArguments(args);
            } catch (Throwable t) {
                throw new Exception(t);
            }
        } else {
            T inst = resultClass.getDeclaredConstructor().newInstance();
            java.sql.ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            for (int i = 1; i <= cols; i++) {
                String label = md.getColumnLabel(i);
                Object val = rs.getObject(i);
                // try setter
                String setter = "set" + Character.toUpperCase(label.charAt(0)) + label.substring(1);
                try {
                    java.lang.reflect.Method m = findMethodIgnoreCase(resultClass, setter, 1);
                    if (m != null) {
                        m.invoke(inst, val);
                        continue;
                    }
                } catch (Exception ignored) {}
                // try field
                try {
                    java.lang.reflect.Field f = findFieldIgnoreCase(resultClass, label);
                    if (f != null) {
                        f.setAccessible(true);
                        f.set(inst, val);
                    }
                } catch (Exception ignored) {}
            }
            return inst;
        }
    }

    private java.lang.reflect.Method findMethodIgnoreCase(Class<?> clazz, String name, int paramCount) {
        for (java.lang.reflect.Method m : clazz.getMethods()) {
            if (m.getName().equalsIgnoreCase(name) && m.getParameterCount() == paramCount) return m;
        }
        return null;
    }

    private java.lang.reflect.Field findFieldIgnoreCase(Class<?> clazz, String name) {
        for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
            if (f.getName().equalsIgnoreCase(name)) return f;
        }
        return null;
    }

    private String normalizeMainTableAlias(String baseSql, String newAlias, EntityMetadata<?> metadata) {
        String tableName = metadata.tableName();
        // Find the alias currently used in the SQL
        // We look for " FROM tableName alias"
        // Since MetadataBuilder uses "a" by default, we expect " FROM tableName a"

        String upper = baseSql.toUpperCase();
        String fromToken = " FROM " + tableName.toUpperCase();
        int idx = upper.indexOf(fromToken);

        if (idx == -1) return baseSql;

        int afterTableIdx = idx + fromToken.length();

        // Skip spaces
        while (afterTableIdx < baseSql.length() && Character.isWhitespace(baseSql.charAt(afterTableIdx))) {
            afterTableIdx++;
        }

        if (afterTableIdx >= baseSql.length()) return baseSql;

        // Read alias
        int aliasStart = afterTableIdx;
        // Alias ends at whitespace, comma, or parenthesis (if subquery?) or end of string
        while (afterTableIdx < baseSql.length() && !Character.isWhitespace(baseSql.charAt(afterTableIdx)) && baseSql.charAt(afterTableIdx) != ',' && baseSql.charAt(afterTableIdx) != ')') {
            afterTableIdx++;
        }

        String currentAlias = baseSql.substring(aliasStart, afterTableIdx);

        // If "AS" is present, skip it and read next token
        if (currentAlias.equalsIgnoreCase("AS")) {
            while (afterTableIdx < baseSql.length() && Character.isWhitespace(baseSql.charAt(afterTableIdx))) {
                afterTableIdx++;
            }
            aliasStart = afterTableIdx;
            while (afterTableIdx < baseSql.length() && !Character.isWhitespace(baseSql.charAt(afterTableIdx)) && baseSql.charAt(afterTableIdx) != ',' && baseSql.charAt(afterTableIdx) != ')') {
                afterTableIdx++;
            }
            currentAlias = baseSql.substring(aliasStart, afterTableIdx);
        }

        if (currentAlias.isEmpty()) return baseSql; // Should not happen if MetadataBuilder adds alias

        // Replace all occurrences of "currentAlias." with "newAlias."
        // Use word boundary to avoid partial matches
        String replaced = baseSql.replaceAll("\\b" + Pattern.quote(currentAlias) + "\\.", newAlias + ".");

        // Replace the alias declaration in FROM clause
        // We can't just replace all " currentAlias " because it might be used elsewhere (e.g. column name same as alias?)
        // But here we know exactly where the alias declaration is (aliasStart, afterTableIdx)

        // However, we modified baseSql with the first replaceAll, so indices might have shifted if currentAlias.length != newAlias.length

        // Better approach:
        // 1. Replace the alias declaration first? No, then we lose track of what the alias was for the columns.
        // 2. We know currentAlias.

        // Let's do the column replacement first.
        // But wait, if currentAlias is "a", and we have a column "apple", \ba\. matches "a.", so "apple" is safe.

        // What if we have " FROM table a JOIN other b ON a.id = b.id"
        // We replace "a." with "newAlias." -> " FROM table a JOIN other b ON newAlias.id = b.id"
        // Then we need to replace " FROM table a" with " FROM table newAlias"

        // We can use regex for the FROM clause too.
        // " FROM tableName a" -> " FROM tableName newAlias"
        // Be careful with case sensitivity of tableName.

        String fromRegex = "(?i)(\\sFROM\\s+" + Pattern.quote(tableName) + "(?:\\s+AS)?\\s+)" + Pattern.quote(currentAlias) + "\\b";
        replaced = replaced.replaceAll(fromRegex, "$1" + newAlias);

        return replaced;
    }

    // New helper: aggregate projection rows when projection contains List/Collection components
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <P> List<P> aggregateProjectionRows(List<P> rows, br.com.liviacare.worm.orm.registry.ProjectionMetadata proj) {
        if (rows == null || rows.isEmpty()) return rows;
        Class<?>[] compTypes = proj.componentTypes();
        boolean hasCollection = false;
        for (Class<?> ct : compTypes) {
            if (ct == List.class || ct == java.util.Collection.class) { hasCollection = true; break; }
        }
        if (!hasCollection) return rows;

        Class<?> projClass = proj.projectionClass();
        java.lang.reflect.RecordComponent[] rcs = projClass.getRecordComponents();
        // Build map: key = List of non-collection component values, value = merged args array
        java.util.Map<List<Object>, Object[]> map = new java.util.LinkedHashMap<>();

        try {
            for (P row : rows) {
                Object[] args = new Object[rcs.length];
                List<Object> keyParts = new ArrayList<>();
                for (int i = 0; i < rcs.length; i++) {
                    String name = rcs[i].getName();
                    java.lang.reflect.Method acc = projClass.getMethod(name);
                    Object val = acc.invoke(row);
                    args[i] = val;
                    Class<?> expected = compTypes[i];
                    if (expected == List.class || expected == java.util.Collection.class) {
                        // list component, will be merged
                        // initialize merged container if not present later
                    } else {
                        keyParts.add(val);
                    }
                }

                Object[] existing = map.get(keyParts);
                if (existing == null) {
                    // First occurrence: copy args, but ensure collection components are mutable lists
                    Object[] copy = new Object[args.length];
                    for (int i = 0; i < args.length; i++) {
                        Class<?> expected = compTypes[i];
                        if (expected == List.class || expected == java.util.Collection.class) {
                            List<Object> lst = new ArrayList<>();
                            if (args[i] instanceof java.util.Collection) lst.addAll((java.util.Collection) args[i]);
                            copy[i] = lst;
                        } else {
                            copy[i] = args[i];
                        }
                    }
                    map.put(keyParts, copy);
                } else {
                    // Merge collection components
                    for (int i = 0; i < args.length; i++) {
                        Class<?> expected = compTypes[i];
                        if (expected == List.class || expected == java.util.Collection.class) {
                            List<Object> merged = (List<Object>) existing[i];
                            if (args[i] instanceof java.util.Collection) {
                                for (Object item : (java.util.Collection) args[i]) {
                                    if (item != null && !merged.contains(item)) merged.add(item);
                                }
                            } else {
                                if (args[i] != null && !merged.contains(args[i])) merged.add(args[i]);
                            }
                        }
                    }
                }
            }

            // Build result list by instantiating projection for each map entry
            List<P> result = new ArrayList<>();
            java.lang.invoke.MethodHandle ctor = proj.constructor();
            for (Object[] mergedArgs : map.values()) {
                // For collection args, convert to immutable list to pass to record constructor if needed
                Object[] finalArgs = new Object[mergedArgs.length];
                for (int i = 0; i < mergedArgs.length; i++) {
                    if (compTypes[i] == List.class || compTypes[i] == java.util.Collection.class) {
                        List<Object> lst = (List<Object>) mergedArgs[i];
                        finalArgs[i] = List.copyOf(lst);
                    } else {
                        finalArgs[i] = mergedArgs[i];
                    }
                }
                P inst = (P) ctor.invokeWithArguments(finalArgs);
                result.add(inst);
            }
            return result;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to aggregate projection rows", e);
        }
    }
}
