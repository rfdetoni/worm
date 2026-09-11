package com.github.rfdetoni.worm.orm.mapping;

import com.github.rfdetoni.worm.annotation.mapping.DbJoin;
import com.github.rfdetoni.worm.orm.registry.EntityMetadata;
import com.github.rfdetoni.worm.orm.registry.EntityRegistry;
import com.github.rfdetoni.worm.orm.registry.JoinInfo;
import com.github.rfdetoni.worm.orm.sql.QueryBuilder;
import com.github.rfdetoni.worm.query.FilterBuilder;
import com.github.rfdetoni.worm.query.Pageable;
import com.github.rfdetoni.worm.util.AliasUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.lang.invoke.MethodHandle;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Executes the batch-fetch strategy for {@code @DbJoin(fetchMode = FetchMode.BATCH)}
 * collection fields.
 *
 * <p>The parent query and child queries intentionally use invocation-local row plans. Result-set
 * metadata is stable for the duration of a query, so retaining those plans in ThreadLocal maps
 * provides little benefit on virtual threads and can retain entity metadata on pooled platform
 * threads. Local holders give the same per-query reuse without thread lifetime coupling.</p>
 */
public final class BatchFetchExecutor {

    private static final Logger log = LoggerFactory.getLogger(BatchFetchExecutor.class);

    /** Maximum number of IDs per IN clause. Larger lists are split into chunks. */
    public static final int IN_CHUNK_SIZE = 1000;

    private BatchFetchExecutor() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> List<T> execute(
            JdbcClient jdbcClient,
            EntityMetadata<T> metadata,
            FilterBuilder filter,
            Pageable pageable) {

        FilterBuilder parentFilter = copyFilterWithoutCollectionJoins(filter);
        QueryBuilder<T> queryBuilder = new QueryBuilder<>(metadata, parentFilter, null);
        String parentSql = queryBuilder.buildSelectSql(pageable, pageable != null);
        List<Object> parentParams = queryBuilder.getParameters();

        EntityMapper.EntityRowPlan[] parentPlan = new EntityMapper.EntityRowPlan[1];
        List<T> parents = jdbcClient.sql(parentSql).params(parentParams)
                .query((resultSet, _) -> {
                    try {
                        if (parentPlan[0] == null) {
                            parentPlan[0] = EntityMapper.prepareEntityRowPlan(resultSet, metadata);
                        }
                        return EntityMapper.mapRow(resultSet, metadata, parentPlan[0]);
                    } catch (java.sql.SQLException exception) {
                        throw new RuntimeException(exception);
                    }
                })
                .list();

        if (parents.isEmpty()) {
            return parents;
        }

        List<Object> parentIds = new ArrayList<>(parents.size());
        MethodHandle idGetter = metadata.idGetter();
        for (T parent : parents) {
            try {
                parentIds.add(idGetter.invoke(parent));
            } catch (Throwable exception) {
                throw new RuntimeException("Failed to read parent ID for batch fetch", exception);
            }
        }

        JoinInfo[] joinInfos = metadata.joinInfos();
        String[] paramLabels = metadata.paramColumnLabels();
        int paramCount = metadata.paramCount();

        for (int parameterIndex = 0; parameterIndex < paramCount; parameterIndex++) {
            if (paramLabels[parameterIndex] != null) {
                continue;
            }

            JoinInfo joinInfo = joinInfos[parameterIndex];
            if (joinInfo == null || !joinInfo.isList() || joinInfo.getFetchMode() != DbJoin.FetchMode.BATCH) {
                continue;
            }

            String foreignKeyColumn = joinInfo.getMappedByColumn();
            if (foreignKeyColumn == null || foreignKeyColumn.isBlank()) {
                log.warn("[WORM] BATCH join on {} has no mappedByColumn — skipping child fetch for field",
                        metadata.entityClass().getSimpleName());
                continue;
            }

            List<Object> children = fetchChildren(jdbcClient, joinInfo, foreignKeyColumn, parentIds);
            Map<Object, List<Object>> childrenByForeignKey = groupByFk(children, joinInfo, foreignKeyColumn);
            injectIntoParents(parents, parentIds, childrenByForeignKey, joinInfo, parameterIndex, metadata);
        }

        return parents;
    }

    private static FilterBuilder copyFilterWithoutCollectionJoins(FilterBuilder original) {
        FilterBuilder copy = FilterBuilder.empty();
        if (original.isIgnoreSoftDelete()) {
            copy.ignoreSoftDelete();
        }
        copy.notJoin();

        String where = original.getWhereClause();
        if (where != null && !where.isBlank()) {
            copy.rawWhere(where, original.getParameters());
        }
        for (FilterBuilder.Join join : original.getJoins()) {
            copy.join(join.type(), join.table(), join.alias(), join.on());
        }
        if (original.hasOrderBy()) {
            copy.orderByRaw(original.buildOrderBy().replace("ORDER BY ", "").trim());
        }
        return copy;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<Object> fetchChildren(
            JdbcClient jdbcClient,
            JoinInfo joinInfo,
            String foreignKeyColumn,
            List<Object> parentIds) {

        EntityMetadata<?> childMetadata = EntityRegistry.getMetadata(joinInfo.getJoinClass());
        List<Object> children = new ArrayList<>();
        String childAlias = joinInfo.getAlias() != null && !joinInfo.getAlias().isBlank()
                ? joinInfo.getAlias()
                : (joinInfo.getTable() != null && !joinInfo.getTable().isBlank()
                ? AliasUtils.defaultMainAlias(joinInfo.getTable())
                : AliasUtils.defaultMainAlias(AliasUtils.entityTableName(joinInfo.getJoinClass())));

        EntityMapper.EntityRowPlan[] childPlan = new EntityMapper.EntityRowPlan[1];
        int[][] joinIndexes = new int[1][];

        for (int start = 0; start < parentIds.size(); start += IN_CHUNK_SIZE) {
            int end = Math.min(start + IN_CHUNK_SIZE, parentIds.size());
            List<Object> chunk = parentIds.subList(start, end);
            String sql = buildChildSql(joinInfo, childMetadata, childAlias, foreignKeyColumn, chunk.size());

            List<Object> chunkChildren = jdbcClient.sql(sql).params(new ArrayList<>(chunk))
                    .query((resultSet, _) -> {
                        try {
                            if (childMetadata != null) {
                                if (childPlan[0] == null) {
                                    childPlan[0] = EntityMapper.prepareEntityRowPlan(resultSet, childMetadata);
                                }
                                return EntityMapper.mapRow(resultSet, (EntityMetadata) childMetadata, childPlan[0]);
                            }
                            if (joinIndexes[0] == null) {
                                joinIndexes[0] = resolveJoinIndexes(joinInfo, resultSet);
                            }
                            return mapJoinRow(resultSet, joinInfo, joinIndexes[0]);
                        } catch (java.sql.SQLException exception) {
                            throw new RuntimeException(exception);
                        }
                    })
                    .list();
            children.addAll(chunkChildren);
        }
        return children;
    }

    private static String buildChildSql(
            JoinInfo joinInfo,
            EntityMetadata<?> childMetadata,
            String alias,
            String foreignKeyColumn,
            int idCount) {

        StringBuilder sql = new StringBuilder();
        if (childMetadata != null) {
            String childSelect = childMetadata.selectSql();
            sql.append(childSelect);
            sql.append(childSelect.toUpperCase(Locale.ROOT).contains(" WHERE ") ? " AND " : " WHERE ");
        } else {
            sql.append("SELECT ");
            List<String> columns = joinInfo.getJoinColumnNames();
            for (int index = 0; index < columns.size(); index++) {
                if (index > 0) {
                    sql.append(", ");
                }
                sql.append(alias).append('.').append(columns.get(index))
                        .append(" AS ").append(alias).append('_').append(columns.get(index));
            }
            sql.append(" FROM ").append(joinInfo.getTable()).append(' ').append(alias).append(" WHERE ");
        }

        sql.append(alias).append('.').append(foreignKeyColumn).append(" IN (");
        for (int index = 0; index < idCount; index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append('?');
        }
        return sql.append(')').toString();
    }

    private static int[] resolveJoinIndexes(JoinInfo joinInfo, ResultSet resultSet) throws java.sql.SQLException {
        List<String> labels = joinInfo.getResultLabels();
        int[] indexes = new int[labels.size()];
        var metadata = resultSet.getMetaData();
        Map<String, Integer> labelToIndex = new HashMap<>(metadata.getColumnCount() * 2);
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            labelToIndex.putIfAbsent(metadata.getColumnLabel(index).toLowerCase(Locale.ROOT), index);
        }
        for (int index = 0; index < labels.size(); index++) {
            indexes[index] = labelToIndex.getOrDefault(labels.get(index).toLowerCase(Locale.ROOT), -1);
        }
        return indexes;
    }

    private static Object mapJoinRow(ResultSet resultSet, JoinInfo joinInfo, int[] indexes)
            throws java.sql.SQLException {
        try {
            List<String> labels = joinInfo.getResultLabels();
            ColumnConverter[] converters = joinInfo.getJoinConverters();
            Object[] arguments = new Object[labels.size()];
            for (int index = 0; index < labels.size(); index++) {
                int resultIndex = indexes != null && index < indexes.length ? indexes[index] : -1;
                Object raw = resultIndex > 0 ? resultSet.getObject(resultIndex) : null;
                arguments[index] = converters != null && index < converters.length && converters[index] != null
                        ? converters[index].convert(raw)
                        : raw;
            }

            if (joinInfo.isRecord()) {
                MethodHandle spreader = joinInfo.getJoinConstructorSpreader();
                return spreader != null
                        ? spreader.invoke(arguments)
                        : joinInfo.getJoinConstructor().invokeWithArguments(arguments);
            }

            Object instance = joinInfo.getJoinConstructor().invoke();
            MethodHandle[] setters = joinInfo.getJoinSetters();
            for (int index = 0; index < setters.length && index < arguments.length; index++) {
                if (setters[index] != null) {
                    setters[index].invoke(instance, arguments[index]);
                }
            }
            return instance;
        } catch (java.sql.SQLException exception) {
            throw exception;
        } catch (Throwable exception) {
            throw new java.sql.SQLException(
                    "Failed to map join row for " + joinInfo.getJoinClass().getName(), exception);
        }
    }

    private static Map<Object, List<Object>> groupByFk(
            List<Object> children,
            JoinInfo joinInfo,
            String foreignKeyColumn) {

        Map<Object, List<Object>> grouped = new LinkedHashMap<>();
        EntityMetadata<?> childMetadata = EntityRegistry.getMetadata(joinInfo.getJoinClass());

        for (Object child : children) {
            Object foreignKeyValue = null;
            try {
                if (childMetadata != null) {
                    int columnIndex = childMetadata.columnIndex(foreignKeyColumn);
                    if (columnIndex >= 0
                            && childMetadata.selectGetters() != null
                            && columnIndex < childMetadata.selectGetters().length) {
                        foreignKeyValue = childMetadata.selectGetters()[columnIndex].invoke(child);
                    }
                }
                if (foreignKeyValue == null) {
                    int joinColumnIndex = joinInfo.getJoinColumnNames().indexOf(foreignKeyColumn);
                    if (joinColumnIndex >= 0
                            && joinInfo.getJoinAccessors() != null
                            && joinColumnIndex < joinInfo.getJoinAccessors().length) {
                        foreignKeyValue = joinInfo.getJoinAccessors()[joinColumnIndex].invoke(child);
                    }
                }
            } catch (Throwable exception) {
                log.debug("[WORM] BatchFetch: failed to read FK '{}' from child — skipping",
                        foreignKeyColumn, exception);
                continue;
            }

            if (foreignKeyValue != null) {
                grouped.computeIfAbsent(foreignKeyValue, ignored -> new ArrayList<>()).add(child);
            }
        }
        return grouped;
    }

    @SuppressWarnings("unchecked")
    private static <T> void injectIntoParents(
            List<T> parents,
            List<Object> parentIds,
            Map<Object, List<Object>> childrenByForeignKey,
            JoinInfo joinInfo,
            int parameterIndex,
            EntityMetadata<T> metadata) {

        for (int index = 0; index < parents.size(); index++) {
            T parent = parents.get(index);
            List<Object> children = childrenByForeignKey.getOrDefault(parentIds.get(index), Collections.emptyList());

            try {
                if (metadata.isRecord()) {
                    Object[] arguments = extractRecordArgs(parent);
                    arguments[parameterIndex] = List.copyOf(children);
                    MethodHandle spreader = metadata.constructorSpreader();
                    T rebuilt = (T) (spreader != null
                            ? spreader.invoke(arguments)
                            : metadata.constructor().invokeWithArguments(arguments));
                    parents.set(index, rebuilt);
                    continue;
                }

                java.lang.reflect.Field field = joinInfo.getJoinField();
                if (field != null) {
                    field.set(parent, children);
                } else {
                    MethodHandle setter = metadata.paramSetters()[parameterIndex];
                    if (setter != null) {
                        setter.invoke(parent, children);
                    }
                }
            } catch (Throwable exception) {
                throw new RuntimeException(
                        "Failed to inject batch-fetched children into parent at index " + index, exception);
            }
        }
    }

    private static Object[] extractRecordArgs(Object entity) {
        java.lang.reflect.RecordComponent[] components = entity.getClass().getRecordComponents();
        Object[] arguments = new Object[components.length];
        for (int index = 0; index < components.length; index++) {
            try {
                arguments[index] = components[index].getAccessor().invoke(entity);
            } catch (Exception exception) {
                throw new RuntimeException(
                        "Failed to read record component " + index + " for batch inject", exception);
            }
        }
        return arguments;
    }
}
