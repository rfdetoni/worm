package br.com.liviacare.worm.orm.mapping;

import br.com.liviacare.worm.annotation.mapping.DbJoin;
import br.com.liviacare.worm.orm.registry.EntityMetadata;
import br.com.liviacare.worm.orm.registry.EntityRegistry;
import br.com.liviacare.worm.orm.registry.JoinInfo;
import br.com.liviacare.worm.query.FilterBuilder;
import br.com.liviacare.worm.query.Pageable;
import br.com.liviacare.worm.util.AliasUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.lang.invoke.MethodHandle;
import java.sql.ResultSet;
import java.util.*;

/**
 * Executes the "batch-fetch" strategy for {@code @DbJoin(fetchMode = FetchMode.BATCH)}
 * collection fields.
 *
 * <p>Instead of the standard Cartesian LEFT JOIN (which multiplies rows:
 * N parents × M children = N×M rows), this executor runs two separate SQL queries:
 * <ol>
 *   <li><b>Parent query</b>: fetch all parent rows without collection joins.</li>
 *   <li><b>Child query per BATCH join</b>: {@code SELECT … FROM child WHERE fk IN (ids…)},
 *       chunked to ≤ {@value #IN_CHUNK_SIZE} IDs per statement.</li>
 * </ol>
 * Children are grouped in-memory by FK and injected into their parent's field.
 *
 * <p><strong>Transactional note</strong>: both queries execute within the same active
 * Spring transaction (if present) because they share the same {@link JdbcClient}.
 */
public final class BatchFetchExecutor {

    private static final Logger log = LoggerFactory.getLogger(BatchFetchExecutor.class);

    /** Maximum number of IDs per IN clause. Larger lists are split into chunks. */
    public static final int IN_CHUNK_SIZE = 1000;

    private BatchFetchExecutor() {}

    /**
     * Executes a parent+batch-child fetch and returns a fully hydrated list of parent entities.
     *
     * @param jdbcClient  live JDBC client
     * @param metadata    parent entity metadata
     * @param filter      filter for the parent query — collection joins are suppressed internally
     * @param pageable    optional pagination for the parent query
     * @param <T>         parent entity type
     * @return list of parent entities with all BATCH-fetch collection fields populated
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> List<T> execute(
            JdbcClient jdbcClient,
            EntityMetadata<T> metadata,
            FilterBuilder filter,
            Pageable pageable) {

        // ── Step 1: Build parent SELECT without collection joins ──────────────
        // We suppress all BATCH-fetch joins from the base query so the result set
        // is clean (no Cartesian multiplication).
        FilterBuilder parentFilter = buildParentFilter(filter, metadata);
        br.com.liviacare.worm.orm.sql.QueryBuilder<T> qb =
                new br.com.liviacare.worm.orm.sql.QueryBuilder<>(metadata, parentFilter, null);
        String parentSql = qb.buildSelectSql(pageable, pageable != null);
        List<Object> parentParams = qb.getParameters();

        List<T> parents = jdbcClient.sql(parentSql).params(parentParams)
                .query((rs, _) -> {
                    try {
                        return EntityMapper.mapRow(rs, metadata, ensureParentPlan(metadata, rs));
                    } catch (java.sql.SQLException e) {
                        throw new RuntimeException(e);
                    }
                })
                .list();

        if (parents.isEmpty()) return parents;

        // ── Step 2: Collect parent IDs ────────────────────────────────────────
        List<Object> parentIds = new ArrayList<>(parents.size());
        MethodHandle idGetter = metadata.idGetter();
        for (T parent : parents) {
            try {
                parentIds.add(idGetter.invoke(parent));
            } catch (Throwable e) {
                throw new RuntimeException("Failed to read parent ID for batch fetch", e);
            }
        }

        // ── Step 3: For each BATCH-fetch join, run the child IN query ─────────
        JoinInfo[] joinInfos = metadata.joinInfos();
        String[] paramLabels = metadata.paramColumnLabels();
        int paramCount = metadata.paramCount();

        for (int pi = 0; pi < paramCount; pi++) {
            if (paramLabels[pi] != null) continue; // scalar column
            JoinInfo ji = joinInfos[pi];
            if (ji == null || !ji.isList()) continue;
            if (ji.getFetchMode() != DbJoin.FetchMode.BATCH) continue;

            String fkColumn = ji.getMappedByColumn();
            if (fkColumn == null || fkColumn.isBlank()) {
                log.warn("[WORM] BATCH join on {} has no mappedByColumn — skipping child fetch for field",
                        metadata.entityClass().getSimpleName());
                continue;
            }

            // Fetch all children for all parent IDs (chunked)
            List<Object> allChildren = fetchChildren(jdbcClient, ji, fkColumn, parentIds, metadata);

            // Group children by FK value
            Map<Object, List<Object>> childrenByFk = groupByFk(allChildren, ji, fkColumn);

            // Inject into parents
            injectIntoParents(parents, parentIds, childrenByFk, ji, pi, metadata);
        }

        return parents;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Builds a parent-only FilterBuilder that suppresses collection BATCH joins
     * but keeps all other filter clauses (WHERE, ORDER, scalar joins).
     */
    private static <T> FilterBuilder buildParentFilter(FilterBuilder original, EntityMetadata<T> metadata) {
        // Use notJoin() so QueryBuilder strips the @DbJoin annotations from the SELECT.
        // Then re-add any explicit filter joins that are NOT batch-fetch collection joins.
        FilterBuilder pf = FilterBuilder.empty();
        if (original.isIgnoreSoftDelete()) pf.ignoreSoftDelete();
        // Copy WHERE and ORDER clauses by merging them back via a new builder
        // The simplest way: replicate the clauses via a sub-filter that wraps the original
        // but forces notJoin() on the base metadata-defined joins.
        // We achieve this by creating a new FilterBuilder that wraps the raw
        // where clause + params from the original, then calling notJoin().
        return copyFilterWithoutCollectionJoins(original, metadata);
    }

    private static <T> FilterBuilder copyFilterWithoutCollectionJoins(FilterBuilder original, EntityMetadata<T> meta) {
        // We want to inherit all WHERE, ORDER, params from the original filter,
        // but suppress the metadata-defined collection joins.
        // The easiest safe approach: rebuild using the original filter instance
        // but tagged with notJoin() — QueryBuilder.stripJoinsFromSql handles the rest.
        // For explicit filter.getJoins() (user-added joins), keep them.
        FilterBuilder copy = FilterBuilder.empty();
        if (original.isIgnoreSoftDelete()) copy.ignoreSoftDelete();
        copy.notJoin(); // suppress @DbJoin-annotated collection joins from the SQL base

        // Re-add user-supplied WHERE clause parameters directly by reusing the original
        // filter's where + params. Since FilterBuilder doesn't expose a "copy" constructor
        // we re-apply the raw clause.
        String where = original.getWhereClause();
        if (where != null && !where.isBlank()) {
            copy.rawWhere(where, original.getParameters());
        }
        for (FilterBuilder.Join fj : original.getJoins()) {
            copy.join(fj.type(), fj.table(), fj.alias(), fj.on());
        }
        if (original.hasOrderBy()) {
            copy.orderByRaw(original.buildOrderBy().replace("ORDER BY ", "").trim());
        }
        return copy;
    }

    private static <T> List<Object> fetchChildren(
            JdbcClient jdbcClient,
            JoinInfo ji,
            String fkColumn,
            List<Object> parentIds,
            EntityMetadata<T> parentMeta) {

        // Try to get registered child metadata for proper mapping
        EntityMetadata<?> childMeta = EntityRegistry.getMetadata(ji.getJoinClass());

        List<Object> allChildren = new ArrayList<>();
        String childAlias = ji.getAlias() != null && !ji.getAlias().isBlank()
                ? ji.getAlias()
                : (ji.getTable() != null && !ji.getTable().isBlank()
                    ? AliasUtils.defaultMainAlias(ji.getTable())
                    : AliasUtils.defaultMainAlias(AliasUtils.entityTableName(ji.getJoinClass())));

        // Chunk parent IDs to avoid overly large IN clauses
        int total = parentIds.size();
        for (int start = 0; start < total; start += IN_CHUNK_SIZE) {
            int end = Math.min(start + IN_CHUNK_SIZE, total);
            List<Object> chunk = parentIds.subList(start, end);

            String sql = buildChildSql(ji, childMeta, childAlias, fkColumn, chunk.size());
            List<Object> params = new ArrayList<>(chunk);

            List<Object> chunkChildren = jdbcClient.sql(sql).params(params)
                    .query((rs, _) -> {
                        try {
                            if (childMeta != null) {
                                return EntityMapper.mapRow(rs, (EntityMetadata) childMeta, ensureChildPlan((EntityMetadata<?>) childMeta, rs));
                            }
                            return mapJoinRow(rs, ji, ensureJoinIndexes(ji, rs));
                        } catch (java.sql.SQLException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .list();
            allChildren.addAll(chunkChildren);
        }
        return allChildren;
    }

    private static String buildChildSql(JoinInfo ji, EntityMetadata<?> childMeta, String alias,
                                        String fkColumn, int idCount) {
        String table = ji.getTable();
        StringBuilder sb = new StringBuilder();

        if (childMeta != null) {
            // Use the child's pre-built SELECT SQL and append a WHERE
            String childSelect = childMeta.selectSql();
            sb.append(childSelect);
            // The child selectSql may already have a WHERE (e.g., soft-delete). We need to add our IN clause.
            String upper = childSelect.toUpperCase();
            sb.append(upper.contains(" WHERE ") ? " AND " : " WHERE ");
        } else {
            // Fallback: select all join columns
            sb.append("SELECT ");
            List<String> cols = ji.getJoinColumnNames();
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(alias).append('.').append(cols.get(i))
                  .append(" AS ").append(alias).append('_').append(cols.get(i));
            }
            sb.append(" FROM ").append(table).append(' ').append(alias);
            sb.append(" WHERE ");
        }

        // Append: alias.fk_col IN (?, ?, ...)
        sb.append(alias).append('.').append(fkColumn).append(" IN (");
        for (int i = 0; i < idCount; i++) {
            if (i > 0) sb.append(", ");
            sb.append('?');
        }
        sb.append(')');
        return sb.toString();
    }

    private static final ThreadLocal<Map<Class<?>, EntityMapper.EntityRowPlan>> PARENT_PLAN_CACHE = ThreadLocal.withInitial(HashMap::new);
    private static final ThreadLocal<Map<Class<?>, EntityMapper.EntityRowPlan>> CHILD_PLAN_CACHE = ThreadLocal.withInitial(HashMap::new);
    private static final ThreadLocal<Map<String, int[]>> JOIN_INDEX_CACHE = ThreadLocal.withInitial(HashMap::new);

    private static EntityMapper.EntityRowPlan ensureParentPlan(EntityMetadata<?> metadata, ResultSet rs) throws java.sql.SQLException {
        return PARENT_PLAN_CACHE.get().computeIfAbsent(metadata.entityClass(), ignored -> {
            try {
                // PERF: cache parent row index mapping for this ResultSet processing thread.
                return EntityMapper.prepareEntityRowPlan(rs, metadata);
            } catch (java.sql.SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static EntityMapper.EntityRowPlan ensureChildPlan(EntityMetadata<?> metadata, ResultSet rs) throws java.sql.SQLException {
        return CHILD_PLAN_CACHE.get().computeIfAbsent(metadata.entityClass(), ignored -> {
            try {
                // PERF: cache child row index mapping for this ResultSet processing thread.
                return EntityMapper.prepareEntityRowPlan(rs, metadata);
            } catch (java.sql.SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static int[] ensureJoinIndexes(JoinInfo ji, ResultSet rs) throws java.sql.SQLException {
        final String key = ji.getJoinClass().getName() + "|" + ji.getAlias();
        int[] cached = JOIN_INDEX_CACHE.get().get(key);
        if (cached != null) {
            return cached;
        }
        List<String> labels = ji.getResultLabels();
        int[] indexes = new int[labels.size()];
        var md = rs.getMetaData();
        Map<String, Integer> labelToIndex = new HashMap<>(md.getColumnCount() * 2);
        for (int i = 1; i <= md.getColumnCount(); i++) {
            labelToIndex.putIfAbsent(md.getColumnLabel(i).toLowerCase(Locale.ROOT), i);
        }
        for (int i = 0; i < labels.size(); i++) {
            // PERF: convert string label lookups into positional reads.
            indexes[i] = labelToIndex.getOrDefault(labels.get(i).toLowerCase(Locale.ROOT), -1);
        }
        JOIN_INDEX_CACHE.get().put(key, indexes);
        return indexes;
    }

    /** Maps a single join row into a join class instance using JoinInfo metadata (no EntityMetadata). */
    private static Object mapJoinRow(ResultSet rs, JoinInfo ji, int[] indexes) throws java.sql.SQLException {
        try {
            List<String> labels = ji.getResultLabels();
            ColumnConverter[] convs = ji.getJoinConverters();
            Object[] args = new Object[labels.size()];
            for (int k = 0; k < labels.size(); k++) {
                int idx = indexes != null && k < indexes.length ? indexes[k] : -1;
                Object raw = idx > 0 ? rs.getObject(idx) : null;
                args[k] = (convs != null && k < convs.length && convs[k] != null)
                        ? convs[k].convert(raw) : raw;
            }
            if (ji.isRecord()) {
                MethodHandle spreader = ji.getJoinConstructorSpreader();
                return spreader != null ? spreader.invoke(args) : ji.getJoinConstructor().invokeWithArguments(args);
            } else {
                Object inst = ji.getJoinConstructor().invoke();
                MethodHandle[] setters = ji.getJoinSetters();
                for (int k = 0; k < setters.length && k < args.length; k++) {
                    if (setters[k] != null) setters[k].invoke(inst, args[k]);
                }
                return inst;
            }
        } catch (java.sql.SQLException e) {
            throw e;
        } catch (Throwable e) {
            throw new java.sql.SQLException("Failed to map join row for " + ji.getJoinClass().getName(), e);
        }
    }

    /**
     * Groups child objects by their FK column value.
     * The FK value is extracted by reading the column from the child's metadata or,
     * if unavailable, via the join column name position.
     */
    @SuppressWarnings("unchecked")
    private static Map<Object, List<Object>> groupByFk(List<Object> children, JoinInfo ji, String fkColumn) {
        Map<Object, List<Object>> map = new LinkedHashMap<>();
        EntityMetadata<?> childMeta = EntityRegistry.getMetadata(ji.getJoinClass());

        for (Object child : children) {
            Object fkValue = null;
            try {
                if (childMeta != null) {
                    // Find the getter for fkColumn in child metadata
                    int idx = childMeta.columnIndex(fkColumn);
                    if (idx >= 0 && childMeta.selectGetters() != null && idx < childMeta.selectGetters().length) {
                        fkValue = childMeta.selectGetters()[idx].invoke(child);
                    }
                }
                if (fkValue == null) {
                    // Fallback: look up by column name in join column names
                    List<String> jcols = ji.getJoinColumnNames();
                    int fidx = jcols.indexOf(fkColumn);
                    if (fidx >= 0 && ji.getJoinAccessors() != null && fidx < ji.getJoinAccessors().length) {
                        fkValue = ji.getJoinAccessors()[fidx].invoke(child);
                    }
                }
            } catch (Throwable e) {
                log.debug("[WORM] BatchFetch: failed to read FK '{}' from child — skipping", fkColumn, e);
                continue;
            }
            if (fkValue != null) {
                map.computeIfAbsent(fkValue, k -> new ArrayList<>()).add(child);
            }
        }
        return map;
    }

    /** Injects the matching child list into each parent entity. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> void injectIntoParents(
            List<T> parents,
            List<Object> parentIds,
            Map<Object, List<Object>> childrenByFk,
            JoinInfo ji,
            int paramIndex,
            EntityMetadata<T> metadata) {

        for (int i = 0; i < parents.size(); i++) {
            T parent = parents.get(i);
            Object parentId = parentIds.get(i);
            List<Object> children = childrenByFk.getOrDefault(parentId, Collections.emptyList());

            try {
                if (metadata.isRecord()) {
                    // Records are immutable — reconstruct with the new child list
                    Object[] args = extractRecordArgs(parent, metadata);
                    args[paramIndex] = List.copyOf(children);
                    MethodHandle spreader = metadata.constructorSpreader();
                    T rebuilt = (T) (spreader != null
                            ? spreader.invoke(args)
                            : metadata.constructor().invokeWithArguments(args));
                    parents.set(i, rebuilt);
                } else {
                    // POJO — set the field directly
                    java.lang.reflect.Field field = ji.getJoinField();
                    if (field != null) {
                        field.set(parent, children);
                    } else {
                        MethodHandle setter = metadata.paramSetters()[paramIndex];
                        if (setter != null) setter.invoke(parent, children);
                    }
                }
            } catch (Throwable e) {
                throw new RuntimeException("Failed to inject batch-fetched children into parent at index " + i, e);
            }
        }
    }

    private static <T> Object[] extractRecordArgs(T entity, EntityMetadata<T> metadata) {
        java.lang.reflect.RecordComponent[] components = entity.getClass().getRecordComponents();
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            try {
                args[i] = components[i].getAccessor().invoke(entity);
            } catch (Exception e) {
                throw new RuntimeException("Failed to read record component " + i + " for batch inject", e);
            }
        }
        return args;
    }
}

