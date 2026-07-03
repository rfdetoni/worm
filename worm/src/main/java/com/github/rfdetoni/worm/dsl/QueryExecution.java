package com.github.rfdetoni.worm.dsl;

import com.github.rfdetoni.worm.orm.OrmManagerLocator;
import com.github.rfdetoni.worm.orm.OrmOperations;
import com.github.rfdetoni.worm.orm.registry.EntityMetadata;
import com.github.rfdetoni.worm.orm.registry.EntityRegistry;
import com.github.rfdetoni.worm.spi.ModuleContextProvider;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

final class QueryExecution {

    private static final ConcurrentHashMap<FastPathKey, Boolean> FAST_PATH_ELIGIBILITY = new ConcurrentHashMap<>();

    private QueryExecution() {
    }

    static QueryPlan planFor(AbstractSelectQuery<?> query) {
        QueryShape shape = SqlRenderer.shapeOf(query);
        QueryPlan cached = QueryPlanCache.get(shape);
        if (cached != null) {
            return cached;
        }
        QueryPlan plan = SqlRenderer.renderPlan(query);
        return QueryPlanCache.put(shape, plan);
    }

    static <T> List<T> fetchEntity(EntitySelectQuery<T> query, Class<T> entityType) {
        OrmOperations orm = OrmManagerLocator.getOrmManager();
        List<T> fastById = tryFetchByIdFastPath(query, entityType, orm);
        if (fastById != null) {
            return fastById;
        }

        QueryPlan plan = planFor(query);
        Object[] bindArray = SqlRenderer.collectParamsArray(query);
        return orm.executeRaw(plan.sql(), entityType, bindArray);
    }

    static List<WormRow> fetchRows(ProjectionSelectQuery query) {
        QueryPlan plan = planFor(query);
        List<Object> params = SqlRenderer.collectParams(query);
        OrmOperations orm = OrmManagerLocator.getOrmManager();
        int columns = plan.projectionCount();
        Class<?> rootType = query.from().entityType();
        return withModule(rootType, () -> orm.client().sql(plan.sql()).params(params)
                .query((rs, _) -> WormRow.from(rs, columns))
                .list());
    }

    static <R> List<R> fetchInto(ProjectionSelectQuery query, Class<R> resultType) {
        QueryPlan plan = planFor(query);
        OrmOperations orm = OrmManagerLocator.getOrmManager();
        Object[] bindArray = SqlRenderer.collectParamsArray(query);
        return orm.executeRaw(plan.sql(), resultType, bindArray);
    }

    private static <T> List<T> tryFetchByIdFastPath(EntitySelectQuery<T> query, Class<T> entityType, OrmOperations orm) {
        if (!query.joins().isEmpty() || !query.orderByNodes().isEmpty() || query.limitValue() != null || query.offsetValue() != null) {
            return null;
        }
        if (!(query.whereNode() instanceof ComparisonPredicate comparison) || comparison.operator() != ComparisonOperator.EQ) {
            return null;
        }
        if (!(comparison.left() instanceof Path<?> leftPath) || !(comparison.right() instanceof ValueExpression<?> rightValue)) {
            return null;
        }
        if (leftPath.root().entityType() != entityType || rightValue.value() == null) {
            return null;
        }
        if (!FAST_PATH_ELIGIBILITY.computeIfAbsent(
                new FastPathKey(entityType, leftPath.column()),
                QueryExecution::isFastPathEligible)) {
            return null;
        }

        Object id = rightValue.value();
        return orm.findByIdAsList(entityType, id, query.from().alias());
    }

    private static boolean isFastPathEligible(FastPathKey key) {
        EntityMetadata<?> metadata = EntityRegistry.getMetadata(key.entityType());
        if (metadata == null) {
            return false;
        }
        if ((metadata.joinInfos() != null && metadata.joinInfos().length > 0) || metadata.hasCollectionJoins()) {
            return false;
        }
        return metadata.idColumnName().equals(key.idColumn());
    }

    private static <R> R withModule(Class<?> entityType, Supplier<R> action) {
        if (entityType == null) {
            return action.get();
        }
        EntityMetadata<?> metadata;
        try {
            metadata = EntityRegistry.getMetadata(entityType);
        } catch (RuntimeException ex) {
            return action.get();
        }
        if (metadata == null) {
            return action.get();
        }
        String module = metadata.module();
        if (module == null || module.isBlank()) {
            return action.get();
        }
        if (ModuleContextProvider.get().getCurrentModule() != null) {
            return action.get();
        }
        return ModuleContextProvider.get().withModule(module, action);
    }

    private record FastPathKey(Class<?> entityType, String idColumn) {
    }
}
