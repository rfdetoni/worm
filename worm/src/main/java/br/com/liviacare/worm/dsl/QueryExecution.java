package br.com.liviacare.worm.dsl;

import br.com.liviacare.worm.orm.OrmManagerLocator;
import br.com.liviacare.worm.orm.OrmOperations;
import br.com.liviacare.worm.orm.registry.EntityMetadata;
import br.com.liviacare.worm.orm.registry.EntityRegistry;
import br.com.liviacare.worm.spi.ModuleContextProvider;

import java.util.List;
import java.util.function.Supplier;

final class QueryExecution {

    private QueryExecution() {
    }

    static QueryPlan planFor(AbstractSelectQuery<?> query) {
        QueryShape shape = SqlRenderer.shapeOf(query);
        return QueryPlanCache.getOrBuild(shape, () -> SqlRenderer.renderPlan(query));
    }

    static <T> List<T> fetchEntity(EntitySelectQuery<T> query, Class<T> entityType) {
        QueryPlan plan = planFor(query);
        List<Object> params = SqlRenderer.collectParams(query);
        OrmOperations orm = OrmManagerLocator.getOrmManager();
        Object[] bindArray = params.toArray();
        return withModule(entityType, () -> orm.executeRaw(plan.sql(), entityType, bindArray));
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
        List<Object> params = SqlRenderer.collectParams(query);
        OrmOperations orm = OrmManagerLocator.getOrmManager();
        Object[] bindArray = params.toArray();
        Class<?> rootType = query.from().entityType();
        return withModule(rootType, () -> orm.executeRaw(plan.sql(), resultType, bindArray));
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
}
