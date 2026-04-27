package br.com.liviacare.worm.orm;

import br.com.liviacare.worm.api.iBaseEntity;
import br.com.liviacare.worm.config.WormProperties;
import br.com.liviacare.worm.config.metrics.LatencyRecorder;
import br.com.liviacare.worm.orm.dialect.SqlDialect;
import br.com.liviacare.worm.orm.exception.OrmOperationException;
import br.com.liviacare.worm.orm.mapping.*;
import br.com.liviacare.worm.orm.registry.EntityMetadata;
import br.com.liviacare.worm.orm.registry.EntityRegistry;
import br.com.liviacare.worm.orm.sql.*;
import br.com.liviacare.worm.orm.tracking.EntitySnapshot;
import br.com.liviacare.worm.orm.tracking.SessionSnapshotContext;
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
    private final BulkWriter bulkWriter;
    private final java.util.concurrent.ConcurrentMap<String, String> partialUpdateSqlCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<Class<?>, String> insertSqlCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<Class<?>, String> updateSqlCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<Class<?>, String> deleteSqlCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<Class<?>, String> softDeleteSqlCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<Class<?>, String> saveUpsertSqlCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<Class<?>, String> batchUpsertSqlCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<String, String> pagedSqlCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final boolean parallelMappingEnabled;
    private final int parallelMappingThreshold;
    private final LatencyRecorder latencyRecorder;

    public OrmManager(JdbcClient jdbcClient, WormProperties properties, SqlDialect dialect,
                      DataSource dataSource, PlatformTransactionManager txManager, LatencyRecorder latencyRecorder) {
        this.executor = new SqlExecutor(jdbcClient, dataSource);
        // Use the entity's package logger as an external logger
        Logger entityLogger = LoggerFactory.getLogger("app.orm.sql"); // or use the desired package
        boolean asyncSqlLogEnabled = properties == null || properties.isAsyncSqlLogEnabled();
        int asyncSqlLogQueueSize = properties != null ? properties.getAsyncSqlLogQueueSize() : 8192;
        this.ormLogger = new OrmLogger(log, entityLogger, asyncSqlLogEnabled, asyncSqlLogQueueSize);
        this.dialect = dialect;
        this.batchSize = properties != null ? properties.getBatchSize() : 500;
        this.saveTryUpdateFirst = properties == null || properties.isSaveTryUpdateFirst();
        this.insertStrategy = properties != null ? properties.getInsertStrategy() : WormProperties.InsertStrategy.UPSERT;
        DataSource resolvedDataSource = dataSource != null ? dataSource : this.executor.dataSourceOrNull();
        int copyThreshold = properties != null ? properties.getBulkCopyThreshold() : PostgresBulkWriter.DEFAULT_COPY_THRESHOLD;
        int unnestThreshold = properties != null ? properties.getBulkUnnestThreshold() : PostgresBulkWriter.DEFAULT_UNNEST_THRESHOLD;
        this.bulkWriter = (dialect != null && resolvedDataSource != null)
                ? dialect.createBulkWriter(resolvedDataSource, copyThreshold, unnestThreshold)
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
        this.parallelMappingEnabled = properties != null && properties.isParallelMappingEnabled();
        this.parallelMappingThreshold = properties != null ? properties.getParallelMappingThreshold() : 1000;
        if (this.parallelMappingEnabled) {
            log.info("[WORM] Parallel mapping ATIVO — threshold={} rows (ForkJoinPool.commonPool)", this.parallelMappingThreshold);
        }
        this.latencyRecorder = latencyRecorder;
    }

    public OrmManager(JdbcClient jdbcClient, WormProperties properties, SqlDialect dialect) {
        this(jdbcClient, properties, dialect, null, null, null);
    }

    public OrmManager(JdbcClient jdbcClient, WormProperties properties, SqlDialect dialect, DataSource dataSource) {
        this(jdbcClient, properties, dialect, dataSource, null, null);
    }

    public OrmManager(JdbcClient jdbcClient, WormProperties properties, SqlDialect dialect,
                      DataSource dataSource, PlatformTransactionManager txManager) {
        this(jdbcClient, properties, dialect, dataSource, txManager, null);
    }

    // Backwards-compatible ctor if someone uses it
    public OrmManager(JdbcClient jdbcClient) {
        this(jdbcClient, null, null, null, null, null);
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
            final WritePlan insertPlan = metadata.insertWritePlan();
            final WritePlan updatePlan = metadata.updateWritePlan();
            final boolean fast = FastPathDecisionCache.canUseFastPath(metadata.entityClass(), metadata);

            // ── Path UPSERT: 1 único round-trip independente de ser novo ou existente ──
            if (insertStrategy == WormProperties.InsertStrategy.UPSERT && id != null && !metadata.hasVersion()) {
                execWrite(() -> {
                    if (entity instanceof iBaseEntity base) base.created();
                    validateIdIsPresent(entity, metadata, "save");
                    final String sql = resolveSaveUpsertSql(metadata);
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
                        int rows;
                        if (updatePlan != null) {
                            rows = executeCompiledWrite(SqlConstants.OP_UPDATE, metadata, updatePlan, entity, false);
                        } else {
                            if (entity instanceof iBaseEntity base) {
                                base.updated();
                            }
                            final String updateSql = resolveUpdateSql(metadata);
                            final List<Object> updateParams = fast
                                    ? EntityPersisterFastPath.updateValuesFast(entity, metadata, capturedId)
                                    : EntityPersister.updateValues(entity, metadata, capturedId);
                            if (ormLogger.isDebugEnabled()) {
                                rows = ormLogger.logAndExecute(SqlConstants.OP_UPDATE, updateSql, updateParams,
                                        () -> executor.client().sql(updateSql).params(updateParams).update());
                            } else {
                                rows = executor.client().sql(updateSql).params(updateParams).update();
                            }
                        }
                        if (rows > 0) {
                            return;
                        }
                        validateIdIsPresent(entity, metadata, "save");
                        if (insertPlan != null) {
                            executeCompiledWrite(SqlConstants.OP_INSERT, metadata, insertPlan, entity, true);
                        } else {
                            if (entity instanceof iBaseEntity base) {
                                base.created();
                            }
                            final String insertSql = resolveInsertSql(metadata);
                            final List<Object> insertParams = fast
                                    ? EntityPersisterFastPath.insertValuesFast(entity, metadata)
                                    : EntityPersister.insertValues(entity, metadata);
                            if (ormLogger.isDebugEnabled()) {
                                ormLogger.logAndExecute(SqlConstants.OP_INSERT, insertSql, insertParams,
                                        () -> executor.client().sql(insertSql).params(insertParams).update());
                            } else {
                                executor.client().sql(insertSql).params(insertParams).update();
                            }
                        }
                    });
                } else {
                    attachSnapshot(entity, metadata);
                    execWrite(() -> {
                        validateIdIsPresent(entity, metadata, "save");
                        try {
                            if (insertPlan != null) {
                                executeCompiledWrite(SqlConstants.OP_INSERT, metadata, insertPlan, entity, true);
                            } else {
                                if (entity instanceof iBaseEntity base) {
                                    base.created();
                                }
                                final String insertSql = resolveInsertSql(metadata);
                                final List<Object> insertParams = fast
                                        ? EntityPersisterFastPath.insertValuesFast(entity, metadata)
                                        : EntityPersister.insertValues(entity, metadata);
                                if (ormLogger.isDebugEnabled()) {
                                    ormLogger.logAndExecute(SqlConstants.OP_INSERT, insertSql, insertParams,
                                            () -> executor.client().sql(insertSql).params(insertParams).update());
                                } else {
                                    executor.client().sql(insertSql).params(insertParams).update();
                                }
                            }
                        } catch (Throwable t) {
                            if (!isDuplicateKey(t)) {
                                throw t instanceof RuntimeException re ? re : new RuntimeException(t);
                            }
                            if (updatePlan != null) {
                                executeCompiledWrite(SqlConstants.OP_UPDATE, metadata, updatePlan, entity, false);
                            } else {
                                if (entity instanceof iBaseEntity base) {
                                    base.updated();
                                }
                                final String updateSql = resolveUpdateSql(metadata);
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
                        }
                    });
                    attachSnapshot(entity, metadata);
                }
                return;
            }

            execWrite(() -> {
                validateIdIsPresent(entity, metadata, "save");
                if (insertPlan != null) {
                    executeCompiledWrite(SqlConstants.OP_INSERT, metadata, insertPlan, entity, true);
                } else {
                    if (entity instanceof iBaseEntity base) {
                        base.created();
                    }
                    final String sql = resolveInsertSql(metadata);
                    final List<Object> params = fast
                            ? EntityPersisterFastPath.insertValuesFast(entity, metadata)
                            : EntityPersister.insertValues(entity, metadata);
                    if (ormLogger.isDebugEnabled()) {
                        ormLogger.logAndExecute(SqlConstants.OP_INSERT, sql, params,
                                () -> executor.client().sql(sql).params(params).update());
                    } else {
                        executor.client().sql(sql).params(params).update();
                    }
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
                        executeHardDelete(metadata, id);
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
                executeHardDelete(metadata, id);
            }
        }));
    }

    public <T> int[] deleteAll(List<T> entities) {
        if (entities == null || entities.isEmpty()) return new int[0];
        return deleteAllBatch(entities);
    }

    public <T> void hardDelete(T entity) {
        final EntityMetadata<T> metadata = getRequiredMetadata((Class<T>) entity.getClass());
        withModuleVoid(metadata, () -> {
            execWrite(() -> {
                try {
                    final Object id = metadata.idGetter().invoke(entity);
                    executeHardDelete(metadata, id);
                } catch (OrmOperationException e) {
                    throw e;
                } catch (Throwable e) {
                    throw new OrmOperationException("Failed to hard delete entity: " + entity, e);
                }
            });
            clearSnapshot(entity, metadata);
        });
    }

    public <T, I> void hardDeleteById(Class<T> clazz, I id) {
        final EntityMetadata<T> metadata = getRequiredMetadata(clazz);
        withModuleVoid(metadata, () -> execWrite(() -> executeHardDelete(metadata, id)));
    }

    public <T> int[] hardDeleteAll(List<T> entities) {
        if (entities == null || entities.isEmpty()) return new int[0];
        return hardDeleteAllBatch(entities);
    }

    public <T> int[] hardDeleteAllBatch(List<T> entities) {
        if (entities == null || entities.isEmpty()) return new int[0];
        final EntityMetadata<T> meta = getRequiredMetadata((Class<T>) entities.get(0).getClass());
        return withModule(meta, () -> {
            if (bulkWriter != null && meta.softDeleteSql() == null) {
                int[] unnestResult = bulkWriter.bulkDelete(entities, meta);
                if (unnestResult != null) {
                    for (T entity : entities) {
                        clearSnapshot(entity, meta);
                    }
                    return unnestResult;
                }
            }
            // Fallback: batchUpdate dentro de transação única
            int[] results = execWrite(() -> {
                final String sql = resolveDeleteSql(meta);
                final String entityName = meta.entityClass().getSimpleName();
                final List<Object[]> params = new ArrayList<>(entities.size());
                for (T e : entities) {
                    Object id = validateIdIsPresent(e, meta, "hardDeleteAll");
                    params.add(new Object[]{id});
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

    public <T> int[] upsertAll(List<T> entities) {
        if (entities == null || entities.isEmpty()) return new int[0];
        return upsertAllBatch(entities);
    }

    // </editor-fold>

    // <editor-fold desc="Parallel mapping helpers">

    /**
     * Executes a SQL query using a two-phase pipeline:
     * <ol>
     *   <li><b>Phase 1 – sequential extraction</b>: reads raw column values from the JDBC
     *       {@link java.sql.ResultSet} into {@code Object[]} arrays (one per row).
     *       This phase runs on the JDBC thread, respecting the cursor's sequential nature.</li>
     *   <li><b>Phase 2 – parallel construction</b>: converts raw values and invokes entity
     *       constructors/setters concurrently via {@code parallelStream()} when
     *       {@code parallelMappingEnabled == true} and rows ≥ {@code parallelMappingThreshold};
     *       otherwise falls back to a sequential stream.</li>
     * </ol>
     */
    private <T> List<T> queryAndMap(String sql, List<Object> params, EntityMetadata<T> metadata) {
        final EntityMapper.EntityRowPlan[] planRef = new EntityMapper.EntityRowPlan[1];
        List<Object[]> rawRows = executor.client().sql(sql).params(params)
                .query((rs, _) -> {
                    try {
                        if (planRef[0] == null) {
                            // PERF: compute label-to-index plan once for the whole ResultSet.
                            planRef[0] = EntityMapper.prepareEntityRowPlan(rs, metadata);
                        }
                        return EntityMapper.extractRaw(rs, metadata, planRef[0]);
                    } catch (java.sql.SQLException e) {
                        throw new RuntimeException(e);
                    }
                })
                .list();
        if (rawRows.isEmpty()) return List.of();
        boolean useParallel = parallelMappingEnabled && rawRows.size() >= parallelMappingThreshold;
        var stream = useParallel ? rawRows.parallelStream() : rawRows.stream();
        return stream.map(raw -> {
            try {
                return EntityMapper.mapFromRaw(raw, metadata);
            } catch (Throwable e) {
                throw new RuntimeException("Failed to map row to " + metadata.entityClass().getName(), e);
            }
        }).collect(java.util.stream.Collectors.toList());
    }

    /**
     * Projection variant of {@link #queryAndMap}: two-phase extract + parallel map
     * using {@link EntityMapper#extractRawProjection} and {@link EntityMapper#mapProjectionFromRaw}.
     */
    @SuppressWarnings("unchecked")
    private <P> List<P> queryAndMapProjection(String sql, List<Object> params,
                                              br.com.liviacare.worm.orm.registry.ProjectionMetadata projMeta) {
        final EntityMapper.ProjectionRowPlan[] planRef = new EntityMapper.ProjectionRowPlan[1];
        List<Object[]> rawRows = executor.client().sql(sql).params(params)
                .query((rs, _) -> {
                    try {
                        if (planRef[0] == null) {
                            // PERF: compute projection column indexes once per ResultSet.
                            planRef[0] = EntityMapper.prepareProjectionRowPlan(rs, projMeta);
                        }
                        return EntityMapper.extractRawProjection(rs, projMeta, planRef[0]);
                    } catch (java.sql.SQLException e) {
                        throw new RuntimeException(e);
                    }
                })
                .list();
        if (rawRows.isEmpty()) return List.of();
        boolean useParallel = parallelMappingEnabled && rawRows.size() >= parallelMappingThreshold;
        var stream = useParallel ? rawRows.parallelStream() : rawRows.stream();
        return (List<P>) stream.map(raw -> {
            try {
                return EntityMapper.mapProjectionFromRaw(raw, projMeta);
            } catch (Throwable e) {
                throw new RuntimeException("Failed to map projection row", e);
            }
        }).collect(java.util.stream.Collectors.toList());
    }

    /**
     * Executes a SQL query for an entity with one-to-many collection joins and merges
     * duplicate-PK rows <em>during</em> the ResultSet drain — never holding an N-row
     * intermediate list when unique entities M << total rows N.
     *
     * <p>PERF: uses {@code ResultSetExtractor} to stream the full RS through
     *         {@link EntityMapper#drainAndMergeCollectionJoins} in a single pass.
     */
    private <T> List<T> queryAndMapCollectionJoin(String sql, List<Object> params, EntityMetadata<T> metadata) {
        return executor.client().sql(sql).params(params)
                .query(rs -> {
                    try {
                        // PERF: drain RS directly into LinkedHashMap — avoids N-entity intermediate
                        //       list for Cartesian JOIN output; only M entity instances live at once.
                        return EntityMapper.drainAndMergeCollectionJoins(rs, metadata);
                    } catch (java.sql.SQLException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    // </editor-fold>

    // <editor-fold desc="Read API">

    public <T, I> Optional<T> findById(Class<T> clazz, I id) {
        final EntityMetadata<T> metadata = getRequiredMetadata(clazz);
        return withModule(metadata, () -> {
            long startNanos = System.nanoTime();
            try {
                boolean hasJoins = metadata.joinInfos() != null && metadata.joinInfos().length > 0;

                if (hasJoins) {
                    return findById(clazz, id, AliasUtils.defaultMainAlias(metadata.tableName()));
                }

                final String sql = metadata.idSelectSql();
                final java.util.function.Supplier<Optional<T>> action = () -> {
                    if (metadata.hasCollectionJoins()) {
                        final EntityMapper.EntityRowPlan[] planRef = new EntityMapper.EntityRowPlan[1];
                        List<T> rows = executor.client().sql(sql).param(id)
                                .query((rs, _) -> {
                                    if (planRef[0] == null) {
                                        // PERF: cache entity row plan per query execution.
                                        planRef[0] = EntityMapper.prepareEntityRowPlan(rs, metadata);
                                    }
                                    return EntityMapper.mapRow(rs, metadata, planRef[0]);
                                })
                                .list();
                        List<T> merged = EntityMapper.mergeCollectionJoins(rows, metadata);
                        return merged.isEmpty() ? Optional.empty() : Optional.of(merged.get(0));
                    }
                    final EntityMapper.EntityRowPlan[] planRef = new EntityMapper.EntityRowPlan[1];
                    return executor.client().sql(sql).param(id)
                            .query((rs, _) -> {
                                if (planRef[0] == null) {
                                    // PERF: cache entity row plan per query execution.
                                    planRef[0] = EntityMapper.prepareEntityRowPlan(rs, metadata);
                                }
                                return EntityMapper.mapRow(rs, metadata, planRef[0]);
                            })
                            .optional();
                };

                if (!ormLogger.isDebugEnabled()) {
                    return action.get();
                }
                final List<Object> params = List.of(id);
                return ormLogger.logAndExecute(SqlConstants.OP_SELECT_BY_ID, sql, params, action);
            } finally {
                if (latencyRecorder != null) {
                    latencyRecorder.record("findById", System.nanoTime() - startNanos);
                }
            }
        });
    }

    public <T, I> Optional<T> findById(Class<T> clazz, I id, String mainAlias) {
        final EntityMetadata<T> metadata = getRequiredMetadata(clazz);
        return withModule(metadata, () -> {
            long startNanos = System.nanoTime();
            try {
                boolean hasJoins = metadata.joinInfos() != null && metadata.joinInfos().length > 0;
                String sql;
                if (hasJoins) {
                    sql = metadata.selectSql();
                    if (mainAlias != null && !mainAlias.isBlank()) {
                        sql = normalizeMainTableAlias(sql, mainAlias, metadata);
                    }
                    sql += SqlConstants.WHERE + (mainAlias != null && !mainAlias.isBlank() ? mainAlias + "." : "") + metadata.idColumnName() + " = ?";
                } else {
                    sql = metadata.idSelectSql(mainAlias);
                }
                final String finalSql = sql;
                final java.util.function.Supplier<Optional<T>> action = () -> {
                    if (metadata.hasCollectionJoins()) {
                        // PERF: drain RS directly — avoids N-entity intermediate list (Gap 2 fix).
                        List<T> merged = executor.client().sql(finalSql).param(id)
                                .query(rs -> {
                                    try {
                                        return EntityMapper.drainAndMergeCollectionJoins(rs, metadata);
                                    } catch (java.sql.SQLException e) {
                                        throw new RuntimeException(e);
                                    }
                                });
                        return merged.isEmpty() ? Optional.empty() : Optional.of(merged.get(0));
                    }
                    final EntityMapper.EntityRowPlan[] planRef = new EntityMapper.EntityRowPlan[1];
                    return executor.client().sql(finalSql).param(id)
                            .query((rs, _) -> {
                                if (planRef[0] == null) {
                                    // PERF: cache entity row plan per query execution.
                                    planRef[0] = EntityMapper.prepareEntityRowPlan(rs, metadata);
                                }
                                return EntityMapper.mapRow(rs, metadata, planRef[0]);
                            })
                            .optional();
                };

                if (!ormLogger.isDebugEnabled()) {
                    return action.get();
                }
                final List<Object> params = List.of(id);
                return ormLogger.logAndExecute(SqlConstants.OP_SELECT_BY_ID, finalSql, params, action);
            } finally {
                if (latencyRecorder != null) {
                    latencyRecorder.record("findById", System.nanoTime() - startNanos);
                }
            }
        });
    }

    @Override
    public <T> List<T> findByIdAsList(Class<T> clazz, Object id, String mainAlias) {
        final EntityMetadata<T> metadata = getRequiredMetadata(clazz);
        return withModule(metadata, () -> {
            long startNanos = System.nanoTime();
            try {
                boolean hasJoins = metadata.joinInfos() != null && metadata.joinInfos().length > 0;
                if (hasJoins || metadata.hasCollectionJoins()) {
                    return findById(clazz, id, mainAlias).map(List::of).orElseGet(List::of);
                }

                final String sql = metadata.idSelectSql(mainAlias);
                final java.util.function.Supplier<List<T>> action = () -> {
                    final EntityMapper.EntityRowPlan[] planRef = new EntityMapper.EntityRowPlan[1];
                    return executor.client().sql(sql).param(id)
                            .query((rs, _) -> {
                                if (planRef[0] == null) {
                                    planRef[0] = EntityMapper.prepareEntityRowPlan(rs, metadata);
                                }
                                return EntityMapper.mapRow(rs, metadata, planRef[0]);
                            })
                            .list();
                };
                if (!ormLogger.isDebugEnabled()) {
                    return action.get();
                }
                return ormLogger.logAndExecute(SqlConstants.OP_SELECT_BY_ID, sql, List.of(id), action);
            } finally {
                if (latencyRecorder != null) {
                    latencyRecorder.record("findById", System.nanoTime() - startNanos);
                }
            }
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
                                    // PERF: drain RS directly — avoids N-entity intermediate list (Gap 2 fix).
                                    List<T> merged = executor.client().sql(sql).params(params)
                                            .query(rs -> {
                                                try {
                                                    return EntityMapper.drainAndMergeCollectionJoins(rs, metadata);
                                                } catch (java.sql.SQLException e) {
                                                    throw new RuntimeException(e);
                                                }
                                            });
                                    return merged.isEmpty() ? Optional.empty() : Optional.of(merged.get(0));
                                }
                                final EntityMapper.EntityRowPlan[] planRef = new EntityMapper.EntityRowPlan[1];
                                return executor.client().sql(sql).params(params)
                                        .query((rs, _) -> {
                                            if (planRef[0] == null) {
                                                // PERF: cache entity row plan per query execution.
                                                planRef[0] = EntityMapper.prepareEntityRowPlan(rs, metadata);
                                            }
                                            return EntityMapper.mapRow(rs, metadata, planRef[0]);
                                        })
                                        .optional();
                            }));
            return result;
        });
    }

    public <T> List<T> findAll(Class<T> clazz, FilterBuilder filter) {
        return findAll(clazz, filter, (Pageable) null).content();
    }

    public <T> Slice<T> findAll(Class<T> clazz, FilterBuilder filter, Pageable pageable) {
        final EntityMetadata<T> metadata = getRequiredMetadata(clazz);
        return withModule(metadata, () -> {
            // ── Batch-fetch path: avoids Cartesian JOIN for collection fields ──
            if (metadata.hasBatchFetchJoins()) {
                List<T> batchResults = ormLogger.logAndExecute(SqlConstants.OP_SELECT, "<batch-fetch>", List.of(),
                        () -> executor.timeAndRecord(SqlConstants.OP_SELECT, metadata.entityClass().getSimpleName(),
                                () -> br.com.liviacare.worm.orm.mapping.BatchFetchExecutor.execute(
                                        executor.client(), metadata, filter, pageable)));
                boolean hasNext = false;
                if (pageable != null) {
                    final int pageSize = pageable.pageSize();
                    if (batchResults.size() > pageSize) {
                        batchResults = new ArrayList<>(batchResults.subList(0, pageSize));
                        hasNext = true;
                    }
                }
                return new Slice<>(batchResults, pageable, hasNext);
            }

            final QueryBuilder<T> queryBuilder = new QueryBuilder<>(metadata, filter, dialect);

            final String sql = queryBuilder.buildSelectSql(pageable, true);
            final List<Object> params = queryBuilder.getParameters();

            // PERF: for collection-join entities use drain+merge path that never holds an
            //       N-row intermediate list (Gap 2 fix: reduces heap delta for JOIN queries).
            List<T> results;
            if (metadata.hasCollectionJoins()) {
                results = ormLogger.logAndExecute(SqlConstants.OP_SELECT, sql, params,
                        () -> executor.timeAndRecord(SqlConstants.OP_SELECT, metadata.entityClass().getSimpleName(),
                                () -> queryAndMapCollectionJoin(sql, params, metadata)));
            } else {
                results = ormLogger.logAndExecute(SqlConstants.OP_SELECT, sql, params,
                        () -> executor.timeAndRecord(SqlConstants.OP_SELECT, metadata.entityClass().getSimpleName(),
                                () -> queryAndMap(sql, params, metadata)));
            }

            boolean hasNext = false;
            if (pageable != null) {
                final int pageSize = pageable.pageSize();
                if (results.size() > pageSize) {
                    results = new ArrayList<>(results.subList(0, pageSize));
                    hasNext = true;
                }
            }

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
        java.util.function.Supplier<R> scoped = SessionSnapshotContext.isBound()
                ? action
                : () -> SessionSnapshotContext.runInScope(action);
        if (metadata == null) return scoped.get();
        String module = metadata.module();
        if (module == null || module.isBlank()) return scoped.get();

        if (ModuleContextProvider.get().getCurrentModule() != null) {
            return scoped.get();
        }

        return ModuleContextProvider.get().withModule(module, scoped);
    }

    private void withModuleVoid(EntityMetadata<?> metadata, Runnable action) {
        Runnable scoped = SessionSnapshotContext.isBound()
                ? action
                : () -> SessionSnapshotContext.runInScope(action);
        if (metadata == null) {
            scoped.run();
            return;
        }
        String module = metadata.module();
        if (module == null || module.isBlank()) {
            scoped.run();
            return;
        }
        if (ModuleContextProvider.get().getCurrentModule() != null) {
            scoped.run();
            return;
        }
        ModuleContextProvider.get().withModuleVoid(module, scoped);
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

    private <T> String resolveInsertSql(EntityMetadata<T> metadata) {
        return insertSqlCache.computeIfAbsent(metadata.entityClass(), ignored -> metadata.insertSql());
    }

    private <T> String resolveUpdateSql(EntityMetadata<T> metadata) {
        return updateSqlCache.computeIfAbsent(metadata.entityClass(), ignored -> metadata.updateSql());
    }

    private <T> String resolveDeleteSql(EntityMetadata<T> metadata) {
        return deleteSqlCache.computeIfAbsent(metadata.entityClass(), ignored -> metadata.deleteSql());
    }

    private <T> String resolveSoftDeleteSql(EntityMetadata<T> metadata) {
        return softDeleteSqlCache.computeIfAbsent(metadata.entityClass(), ignored -> metadata.softDeleteSql());
    }

    private <T> String resolveSaveUpsertSql(EntityMetadata<T> metadata) {
        return saveUpsertSqlCache.computeIfAbsent(metadata.entityClass(), ignored -> {
            String upsertSql = metadata.upsertSql();
            return (upsertSql != null && !upsertSql.isBlank()) ? upsertSql : metadata.insertSql();
        });
    }

    private <T> String resolveBatchUpsertSql(EntityMetadata<T> metadata) {
        return batchUpsertSqlCache.computeIfAbsent(metadata.entityClass(), ignored ->
                (this.dialect != null) ? this.dialect.buildUpsertSql(metadata) : metadata.insertSql());
    }

    private <T> void doFullUpdate(T entity, EntityMetadata<T> metadata) {
        final Object id = validateIdIsPresent(entity, metadata, "update");
        if (metadata.updateWritePlan() != null) {
            int rows = execWrite(() -> executeCompiledWrite(SqlConstants.OP_UPDATE, metadata, metadata.updateWritePlan(), entity, false));
            assertOptimisticLock(metadata, entity, id, rows);
            attachSnapshot(entity, metadata);
            return;
        }

        if (entity instanceof iBaseEntity base) {
            base.updated();
        }
        final boolean fast = FastPathDecisionCache.canUseFastPath(metadata.entityClass(), metadata);
        final String sql = resolveUpdateSql(metadata);
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

    private <T> int executeCompiledWrite(String operation, EntityMetadata<T> metadata, WritePlan plan, T entity, boolean insertOperation) {
        EntityBinder<T> binder = metadata.binder();
        if (binder != null) {
            if (ormLogger.isDebugEnabled()) {
                return ormLogger.logAndExecute(operation, plan.sql(), List.of(),
                        () -> executeBoundWrite(plan.sql(), binder, entity, insertOperation));
            }
            return executeBoundWrite(plan.sql(), binder, entity, insertOperation);
        }
        if (ormLogger.isDebugEnabled()) {
            return ormLogger.logAndExecute(operation, plan.sql(), List.of(),
                    () -> plan.execute(executor.client(), entity));
        }
        return plan.execute(executor.client(), entity);
    }

    private <T> int executeBoundWrite(String sql, EntityBinder<T> binder, T entity, boolean insertOperation) {
        JdbcClient.StatementSpec spec = executor.client().sql(sql);
        if (insertOperation) {
            binder.bindInsert(spec, entity);
        } else {
            binder.bindUpdate(spec, entity);
        }
        return spec.update();
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
        return SessionSnapshotContext.get(entity);
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
        SessionSnapshotContext.put(entity, snapshot);
    }

    private <T> void attachSnapshots(List<T> entities, EntityMetadata<T> metadata) {
        if (!metadata.isTracked() || entities == null || entities.isEmpty()) {
            return;
        }
        // Build all snapshots first, then acquire the lock once for the whole batch.
        // The previous implementation called attachSnapshot() per entity, causing
        // N synchronized-block acquisitions per page — O(n) lock overhead.
        Map<Object, EntitySnapshot> batch = null;
        for (T entity : entities) {
            if (entity == null) continue;
            EntitySnapshot snapshot = EntitySnapshot.capture(entity, metadata);
            if (entity instanceof br.com.liviacare.worm.ActiveRecord<?, ?> activeRecord) {
                activeRecord.__wormSetSnapshot(snapshot);
            } else {
                if (batch == null) batch = new java.util.LinkedHashMap<>(entities.size());
                batch.put(entity, snapshot);
            }
        }
        if (batch != null) {
            SessionSnapshotContext.putAll(batch);
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
        SessionSnapshotContext.remove(entity);
    }

    private <T, I> void executeHardDelete(EntityMetadata<T> metadata, I id) {
        final String sql = resolveDeleteSql(metadata);
        final List<Object> params = List.of(id);
        if (ormLogger.isDebugEnabled()) {
            ormLogger.logAndExecute(SqlConstants.OP_DELETE, sql, params,
                    () -> executor.client().sql(sql).param(id).update());
        } else {
            executor.client().sql(sql).param(id).update();
        }
    }

    private <T, I> void softDelete(EntityMetadata<T> metadata, I id) {
        final String sql = resolveSoftDeleteSql(metadata);
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
                int[] copyResult = bulkWriter.bulkInsert(entities, meta);
                if (copyResult != null) {
                    attachSnapshots(entities, meta);
                    return copyResult;
                }
            }
            // Fallback: batchUpdate dentro de transação única
            int[] results = execWrite(() -> {
                final String sql = resolveInsertSql(meta);
                final String entityName = meta.entityClass().getSimpleName();
                final boolean fast = FastPathDecisionCache.canUseFastPath(meta.entityClass(), meta);
                final List<Object[]> params = new ArrayList<>(entities.size());
                final Object[] insertBuffer = fast ? null : new Object[meta.insertableColumns().size()];
                for (T e : entities) {
                    if (e instanceof iBaseEntity base) {
                        base.created();
                    }
                    if (fast) {
                        params.add(EntityPersisterFastPath.insertValuesArrayFast(e, meta));
                    } else {
                        EntityPersister.fillInsertBuffer(e, meta, insertBuffer);
                        // PERF: clone once at add time, reusing the working buffer for all entities.
                        params.add(insertBuffer.clone());
                    }
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
                int[] unnestResult = bulkWriter.bulkUpdate(entities, meta);
                if (unnestResult != null) {
                    attachSnapshots(entities, meta);
                    return unnestResult;
                }
            }
            // Fallback: batchUpdate dentro de transação única
            int[] results = execWrite(() -> {
                final String sql = resolveUpdateSql(meta);
                final String entityName = meta.entityClass().getSimpleName();
                final boolean fast = FastPathDecisionCache.canUseFastPath(meta.entityClass(), meta);
                final List<Object[]> params = new ArrayList<>(entities.size());
                final List<Object> ids = new ArrayList<>(entities.size());
                final List<Object> versions = new ArrayList<>(entities.size());
                final Object[] updateBuffer = fast ? null : new Object[meta.updatableColumns().size() + 1 + (meta.hasVersion() ? 1 : 0)];
                for (T e : entities) {
                    Object id = validateIdIsPresent(e, meta, "updateAll");
                    if (e instanceof iBaseEntity base) {
                        base.updated();
                    }
                    if (fast) {
                        params.add(EntityPersisterFastPath.updateValuesArrayFast(e, meta, id));
                    } else {
                        EntityPersister.fillUpdateBuffer(e, meta, id, updateBuffer);
                        // PERF: clone once at add time, reusing the working buffer for all entities.
                        params.add(updateBuffer.clone());
                    }
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
                int[] unnestResult = bulkWriter.bulkDelete(entities, meta);
                if (unnestResult != null) {
                    for (T entity : entities) {
                        clearSnapshot(entity, meta);
                    }
                    return unnestResult;
                }
            }
            // Fallback: batchUpdate dentro de transação única
            int[] results = execWrite(() -> {
                final String sql = meta.softDeleteSql() != null ? resolveSoftDeleteSql(meta) : resolveDeleteSql(meta);
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
            // ── Bulk fast path (e.g. Postgres unnest UPSERT) ──────────────────
            if (bulkWriter != null) {
                int[] bulkResult = bulkWriter.bulkUpsert(entities, meta);
                if (bulkResult != null) {
                    attachSnapshots(entities, meta);
                    return bulkResult;
                }
            }
            final String sql = resolveBatchUpsertSql(meta);
            final String entityName = meta.entityClass().getSimpleName();
            final List<Object[]> params = new ArrayList<>(entities.size());
            final Object[] upsertBuffer = new Object[meta.insertableColumns().size()];
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
                EntityPersister.fillInsertBuffer(e, meta, upsertBuffer);
                // PERF: clone once at add time, reusing the working buffer for all entities.
                params.add(upsertBuffer.clone());
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
                    () -> executor.timeAndRecord(SqlConstants.OP_SELECT, metadata.entityClass().getSimpleName(), () -> {
                        List<P> list = queryAndMapProjection(sql, params, proj);
                        // If projection has collection components, aggregate rows into single projection per parent
                        return aggregateProjectionRows(list, proj);
                    }));
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
                        final EntityMapper.ProjectionRowPlan[] planRef = new EntityMapper.ProjectionRowPlan[1];
                        @SuppressWarnings("unchecked")
                        Optional<P> opt = (Optional<P>) executor.client().sql(sql).params(params)
                                .query((rs, rn) -> {
                                    try {
                                        if (planRef[0] == null) {
                                            // PERF: cache projection row plan per query execution.
                                            planRef[0] = EntityMapper.prepareProjectionRowPlan(rs, proj);
                                        }
                                        return EntityMapper.mapToProjection(rs, proj, planRef[0]);
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
                        final EntityMapper.ProjectionRowPlan[] planRef = new EntityMapper.ProjectionRowPlan[1];
                        @SuppressWarnings("unchecked")
                        Optional<P> opt = (Optional<P>) executor.client().sql(sql).params(params)
                                .query((rs, rn) -> {
                                    try {
                                        if (planRef[0] == null) {
                                            // PERF: cache projection row plan per query execution.
                                            planRef[0] = EntityMapper.prepareProjectionRowPlan(rs, proj);
                                        }
                                        return EntityMapper.mapToProjection(rs, proj, planRef[0]);
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
        final EntityMapper.EntityRowPlan[] planRef = new EntityMapper.EntityRowPlan[1];
        java.util.function.Supplier<List<T>> supplier = () -> executor.client().sql(sql).params(paramList)
                .query((rs, rn) -> {
                    try {
                        if (md != null) {
                            if (planRef[0] == null) {
                                // PERF: cache entity row plan per raw query execution.
                                planRef[0] = EntityMapper.prepareEntityRowPlan(rs, md);
                            }
                            return EntityMapper.mapRow(rs, md, planRef[0]);
                        }
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
        return results;
    }

    @Override
    public <T> List<T> executeRawPaged(String baseSql, Class<T> resultClass, int limit, long offset, Object... params) {
        String paginatedSql = pagedSqlCache.computeIfAbsent(baseSql, ignored -> baseSql + " LIMIT ? OFFSET ?");
        Object[] input = params == null ? new Object[0] : params;
        Object[] pagedParams = Arrays.copyOf(input, input.length + 2);
        pagedParams[input.length] = limit;
        pagedParams[input.length + 1] = offset;
        return executeRaw(paginatedSql, resultClass, pagedParams);
    }

    public <T> List<T> findAllWithCte(Class<T> entityClass, FilterBuilder filterWithCte) {
        final EntityMetadata<T> metadata = getRequiredMetadata(entityClass);
        return withModule(metadata, () -> {
            final QueryBuilder<T> qb = new QueryBuilder<>(metadata, filterWithCte, dialect);
            final String sql = qb.buildSelectSql(null, true);
            final List<Object> params = qb.getParameters();

            List<T> results = ormLogger.logAndExecute(SqlConstants.OP_SELECT, sql, params,
                    () -> executor.timeAndRecord(SqlConstants.OP_SELECT, metadata.entityClass().getSimpleName(),
                            () -> queryAndMap(sql, params, metadata)));
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

            return ormLogger.logAndExecute(SqlConstants.OP_SELECT, sql, params,
                    () -> executor.timeAndRecord(SqlConstants.OP_SELECT, metadata.entityClass().getSimpleName(), () -> {
                        List<P> list = queryAndMapProjection(sql, params, proj);
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
