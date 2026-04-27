package br.com.liviacare.worm.dsl;

import java.util.ArrayList;
import java.util.List;

final class SqlRenderer {

    private SqlRenderer() {
    }

    static QueryShape shapeOf(AbstractSelectQuery<?> query) {
        String projectionShape = projectionShape(query);
        String joinsShape = joinsShape(query.joins());
        String whereShape = predicateShape(query.whereNode());
        String orderShape = orderShape(query.orderByNodes());
        return new QueryShape(
                query.from().entityType(),
                query.from().tableName(),
                query.from().alias(),
                projectionShape,
                joinsShape,
                whereShape,
                orderShape,
                query.limitValue() != null,
                query.offsetValue() != null,
                query.selectEntity()
        );
    }

    static QueryPlan renderPlan(AbstractSelectQuery<?> query) {
        StringBuilder sql = new StringBuilder(192);
        if (query.selectEntity()) {
            sql.append("SELECT ").append(query.from().alias()).append(".*");
        } else {
            sql.append("SELECT ");
            Expression<?>[] projections = query.projections();
            for (int i = 0; i < projections.length; i++) {
                if (i > 0) sql.append(", ");
                appendProjectionExpressionSql(projections[i], sql);
                sql.append(" AS c").append(i);
            }
        }

        sql.append(" FROM ").append(query.from().tableName()).append(' ').append(query.from().alias());
        appendJoinsSql(query.joins(), sql);
        appendWhereSql(query.whereNode(), sql);
        appendOrderSql(query.orderByNodes(), sql);
        appendLimitOffsetSql(query.limitValue(), query.offsetValue(), sql);

        int projectionCount = query.selectEntity() ? 0 : query.projections().length;
        return new QueryPlan(sql.toString(), projectionCount);
    }

    static List<Object> collectParams(AbstractSelectQuery<?> query) {
        int paramCount = countParams(query);
        if (paramCount == 0) {
            return List.of();
        }
        ArrayList<Object> params = new ArrayList<>(paramCount);
        for (JoinSpec join : query.joins()) {
            collectPredicateParams(join.on(), params);
        }
        collectPredicateParams(query.whereNode(), params);
        if (query.limitValue() != null) {
            params.add(query.limitValue());
        }
        if (query.offsetValue() != null) {
            params.add(query.offsetValue());
        }
        return params;
    }

    static Object[] collectParamsArray(AbstractSelectQuery<?> query) {
        int paramCount = countParams(query);
        if (paramCount == 0) {
            return new Object[0];
        }
        Object[] params = new Object[paramCount];
        int next = 0;
        for (JoinSpec join : query.joins()) {
            next = collectPredicateParams(join.on(), params, next);
        }
        next = collectPredicateParams(query.whereNode(), params, next);
        if (query.limitValue() != null) {
            params[next++] = query.limitValue();
        }
        if (query.offsetValue() != null) {
            params[next] = query.offsetValue();
        }
        return params;
    }

    private static void appendJoinsSql(List<JoinSpec> joins, StringBuilder sql) {
        for (JoinSpec join : joins) {
            sql.append(' ')
                    .append(join.type().sql())
                    .append(" JOIN ")
                    .append(join.target().tableName())
                    .append(' ')
                    .append(join.target().alias())
                    .append(" ON ");
            appendPredicateSql(join.on(), sql);
        }
    }

    private static void appendWhereSql(Predicate where, StringBuilder sql) {
        if (where == null) return;
        sql.append(" WHERE ");
        appendPredicateSql(where, sql);
    }

    private static void appendOrderSql(List<OrderSpecifier> order, StringBuilder sql) {
        if (order.isEmpty()) return;
        sql.append(" ORDER BY ");
        for (int i = 0; i < order.size(); i++) {
            if (i > 0) sql.append(", ");
            OrderSpecifier spec = order.get(i);
            sql.append(spec.path().qualifiedName())
                    .append(' ')
                    .append(spec.direction().name());
        }
    }

    private static void appendLimitOffsetSql(Integer limit, Long offset, StringBuilder sql) {
        if (limit != null) {
            sql.append(" LIMIT ?");
        }
        if (offset != null) {
            sql.append(" OFFSET ?");
        }
    }

    private static void appendProjectionExpressionSql(Expression<?> expression, StringBuilder sql) {
        if (expression instanceof Path<?> path) {
            sql.append(path.qualifiedName());
            return;
        }
        throw new IllegalArgumentException("Projection expression not supported: " + expression.getClass().getName());
    }

    private static void appendPredicateSql(Predicate predicate, StringBuilder sql) {
        if (predicate == null) {
            sql.append("1 = 1");
            return;
        }
        if (predicate instanceof ComparisonPredicate comparison) {
            appendExpressionSql(comparison.left(), sql);
            sql.append(' ').append(comparison.operator().sql()).append(' ');
            appendExpressionSql(comparison.right(), sql);
            return;
        }
        if (predicate instanceof NullCheckPredicate nullCheck) {
            sql.append(nullCheck.path().qualifiedName())
                    .append(nullCheck.isNull() ? " IS NULL" : " IS NOT NULL");
            return;
        }
        if (predicate instanceof BetweenPredicate<?> between) {
            sql.append(between.path().qualifiedName()).append(" BETWEEN ? AND ?");
            return;
        }
        if (predicate instanceof InPredicate<?> in) {
            if (in.values().isEmpty()) {
                sql.append("1 = 0");
                return;
            }
            sql.append(in.path().qualifiedName()).append(" IN (");
            for (int i = 0; i < in.values().size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append('?');
            }
            sql.append(')');
            return;
        }
        if (predicate instanceof LikePredicate like) {
            sql.append(like.path().qualifiedName()).append(" LIKE ?");
            return;
        }
        if (predicate instanceof NotPredicate not) {
            sql.append("NOT (");
            appendPredicateSql(not.inner(), sql);
            sql.append(')');
            return;
        }
        if (predicate instanceof JunctionPredicate junction) {
            sql.append('(');
            Predicate[] predicates = junction.predicates();
            for (int i = 0; i < predicates.length; i++) {
                if (i > 0) {
                    sql.append(junction.junctionType() == JunctionPredicate.Type.AND ? " AND " : " OR ");
                }
                appendPredicateSql(predicates[i], sql);
            }
            sql.append(')');
            return;
        }
        if (predicate instanceof BooleanConstantPredicate constant) {
            sql.append(constant.value() ? "1 = 1" : "1 = 0");
            return;
        }
        throw new IllegalArgumentException("Unsupported predicate node: " + predicate.getClass().getName());
    }

    private static void appendExpressionSql(Expression<?> expression, StringBuilder sql) {
        if (expression instanceof Path<?> path) {
            sql.append(path.qualifiedName());
            return;
        }
        if (expression instanceof ValueExpression<?>) {
            sql.append('?');
            return;
        }
        throw new IllegalArgumentException("Unsupported expression node: " + expression.getClass().getName());
    }

    private static String projectionShape(AbstractSelectQuery<?> query) {
        if (query.selectEntity()) {
            return "ENTITY";
        }
        StringBuilder shape = new StringBuilder(48);
        Expression<?>[] projections = query.projections();
        for (int i = 0; i < projections.length; i++) {
            if (i > 0) shape.append('|');
            Expression<?> expression = projections[i];
            if (expression instanceof Path<?> path) {
                shape.append(path.root().tableName()).append('.')
                        .append(path.root().alias()).append('.')
                        .append(path.column());
            } else {
                shape.append("expr#").append(expression.shapeHash());
            }
        }
        return shape.toString();
    }

    private static String joinsShape(List<JoinSpec> joins) {
        if (joins.isEmpty()) return "";
        StringBuilder shape = new StringBuilder(64);
        for (JoinSpec join : joins) {
            if (!shape.isEmpty()) shape.append('|');
            shape.append(join.type().name())
                    .append(':')
                    .append(join.target().tableName())
                    .append(':')
                    .append(join.target().alias())
                    .append(':');
            appendPredicateShape(join.on(), shape);
        }
        return shape.toString();
    }

    private static String predicateShape(Predicate predicate) {
        if (predicate == null) return "";
        StringBuilder shape = new StringBuilder(64);
        appendPredicateShape(predicate, shape);
        return shape.toString();
    }

    private static void appendPredicateShape(Predicate predicate, StringBuilder shape) {
        if (predicate == null) {
            shape.append("null");
            return;
        }
        if (predicate instanceof ComparisonPredicate comparison) {
            shape.append("cmp(")
                    .append(comparison.operator().name())
                    .append(':')
                    .append(comparison.left().shapeHash())
                    .append(':')
                    .append(comparison.right().shapeHash())
                    .append(')');
            return;
        }
        if (predicate instanceof NullCheckPredicate nullCheck) {
            shape.append("null(")
                    .append(nullCheck.path().shapeHash())
                    .append(':')
                    .append(nullCheck.isNull() ? "1" : "0")
                    .append(')');
            return;
        }
        if (predicate instanceof BetweenPredicate<?> between) {
            shape.append("between(")
                    .append(between.path().shapeHash())
                    .append(':')
                    .append(between.lower().shapeHash())
                    .append(':')
                    .append(between.upper().shapeHash())
                    .append(')');
            return;
        }
        if (predicate instanceof InPredicate<?> in) {
            shape.append("in(")
                    .append(in.path().shapeHash())
                    .append(':')
                    .append(in.values().size())
                    .append(')');
            return;
        }
        if (predicate instanceof LikePredicate like) {
            shape.append("like(")
                    .append(like.path().shapeHash())
                    .append(':')
                    .append(like.mode().name())
                    .append(')');
            return;
        }
        if (predicate instanceof NotPredicate not) {
            shape.append("not(");
            appendPredicateShape(not.inner(), shape);
            shape.append(')');
            return;
        }
        if (predicate instanceof JunctionPredicate junction) {
            shape.append(junction.junctionType().name()).append('(');
            Predicate[] predicates = junction.predicates();
            for (int i = 0; i < predicates.length; i++) {
                if (i > 0) shape.append(',');
                appendPredicateShape(predicates[i], shape);
            }
            shape.append(')');
            return;
        }
        if (predicate instanceof BooleanConstantPredicate constant) {
            shape.append(constant.value() ? "true" : "false");
            return;
        }
        shape.append("unknown#").append(predicate.shapeHash());
    }

    private static String orderShape(List<OrderSpecifier> order) {
        if (order.isEmpty()) return "";
        StringBuilder shape = new StringBuilder(32);
        for (OrderSpecifier spec : order) {
            if (!shape.isEmpty()) shape.append('|');
            shape.append(spec.path().shapeHash()).append(':').append(spec.direction().name());
        }
        return shape.toString();
    }

    private static int countParams(AbstractSelectQuery<?> query) {
        int count = 0;
        for (JoinSpec join : query.joins()) {
            count += countPredicateParams(join.on());
        }
        count += countPredicateParams(query.whereNode());
        if (query.limitValue() != null) {
            count++;
        }
        if (query.offsetValue() != null) {
            count++;
        }
        return count;
    }

    private static int countPredicateParams(Predicate predicate) {
        if (predicate == null) return 0;
        if (predicate instanceof ComparisonPredicate comparison) {
            return countExpressionParams(comparison.left()) + countExpressionParams(comparison.right());
        }
        if (predicate instanceof BetweenPredicate<?> between) {
            return countExpressionParams(between.lower()) + countExpressionParams(between.upper());
        }
        if (predicate instanceof InPredicate<?> in) {
            return in.values().size();
        }
        if (predicate instanceof LikePredicate) {
            return 1;
        }
        if (predicate instanceof NotPredicate not) {
            return countPredicateParams(not.inner());
        }
        if (predicate instanceof JunctionPredicate junction) {
            int count = 0;
            for (Predicate p : junction.predicates()) {
                count += countPredicateParams(p);
            }
            return count;
        }
        return 0;
    }

    private static int countExpressionParams(Expression<?> expression) {
        return expression instanceof ValueExpression<?> ? 1 : 0;
    }

    private static void collectPredicateParams(Predicate predicate, List<Object> out) {
        if (predicate == null) return;
        if (predicate instanceof ComparisonPredicate comparison) {
            collectExpressionParam(comparison.left(), out);
            collectExpressionParam(comparison.right(), out);
            return;
        }
        if (predicate instanceof BetweenPredicate<?> between) {
            out.add(toBindValue(between.lower().value()));
            out.add(toBindValue(between.upper().value()));
            return;
        }
        if (predicate instanceof InPredicate<?> in) {
            for (Object value : in.values()) {
                out.add(toBindValue(value));
            }
            return;
        }
        if (predicate instanceof LikePredicate like) {
            out.add(toLikeBindValue(like.mode(), like.value().value()));
            return;
        }
        if (predicate instanceof NotPredicate not) {
            collectPredicateParams(not.inner(), out);
            return;
        }
        if (predicate instanceof JunctionPredicate junction) {
            for (Predicate p : junction.predicates()) {
                collectPredicateParams(p, out);
            }
            return;
        }
    }

    private static void collectExpressionParam(Expression<?> expression, List<Object> out) {
        if (expression instanceof ValueExpression<?> valueExpression) {
            out.add(toBindValue(valueExpression.value()));
        }
    }

    private static int collectPredicateParams(Predicate predicate, Object[] out, int idx) {
        if (predicate == null) return idx;
        if (predicate instanceof ComparisonPredicate comparison) {
            idx = collectExpressionParam(comparison.left(), out, idx);
            idx = collectExpressionParam(comparison.right(), out, idx);
            return idx;
        }
        if (predicate instanceof BetweenPredicate<?> between) {
            out[idx++] = toBindValue(between.lower().value());
            out[idx++] = toBindValue(between.upper().value());
            return idx;
        }
        if (predicate instanceof InPredicate<?> in) {
            for (Object value : in.values()) {
                out[idx++] = toBindValue(value);
            }
            return idx;
        }
        if (predicate instanceof LikePredicate like) {
            out[idx++] = toLikeBindValue(like.mode(), like.value().value());
            return idx;
        }
        if (predicate instanceof NotPredicate not) {
            return collectPredicateParams(not.inner(), out, idx);
        }
        if (predicate instanceof JunctionPredicate junction) {
            for (Predicate p : junction.predicates()) {
                idx = collectPredicateParams(p, out, idx);
            }
            return idx;
        }
        return idx;
    }

    private static int collectExpressionParam(Expression<?> expression, Object[] out, int idx) {
        if (expression instanceof ValueExpression<?> valueExpression) {
            out[idx++] = toBindValue(valueExpression.value());
        }
        return idx;
    }

    private static Object toBindValue(Object value) {
        if (value instanceof Enum<?> e) return e.name();
        return value;
    }

    private static Object toLikeBindValue(LikeMode mode, String value) {
        if (mode == LikeMode.RAW) return value;
        return switch (mode) {
            case CONTAINS -> "%" + value + "%";
            case STARTS_WITH -> value + "%";
            case ENDS_WITH -> "%" + value;
            case RAW -> value;
        };
    }
}
