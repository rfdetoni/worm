package br.com.liviacare.worm.dsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public abstract class AbstractSelectQuery<Q extends AbstractSelectQuery<Q>> {

    private final EntityPath<?> from;
    private final Expression<?>[] projections;
    private final boolean selectEntity;
    private final List<JoinSpec> joins = new ArrayList<>(2);
    private final List<OrderSpecifier> orderBy = new ArrayList<>(2);
    private Predicate where;
    private Integer limit;
    private Long offset;

    protected AbstractSelectQuery(EntityPath<?> from, Expression<?>[] projections, boolean selectEntity) {
        this.from = Objects.requireNonNull(from, "from cannot be null");
        this.projections = projections == null ? new Expression<?>[0] : projections;
        this.selectEntity = selectEntity;
    }

    public Q where(Predicate predicate) {
        Objects.requireNonNull(predicate, "predicate cannot be null");
        this.where = this.where == null ? predicate : this.where.and(predicate);
        return self();
    }

    public Q orderBy(OrderSpecifier... specifiers) {
        if (specifiers == null || specifiers.length == 0) return self();
        for (OrderSpecifier specifier : specifiers) {
            if (specifier != null) {
                orderBy.add(specifier);
            }
        }
        return self();
    }

    public JoinOnStep<Q> join(EntityPath<?> target) {
        return new JoinOnStep<>(self(), JoinType.INNER, target);
    }

    public JoinOnStep<Q> leftJoin(EntityPath<?> target) {
        return new JoinOnStep<>(self(), JoinType.LEFT, target);
    }

    public JoinOnStep<Q> rightJoin(EntityPath<?> target) {
        return new JoinOnStep<>(self(), JoinType.RIGHT, target);
    }

    public JoinOnStep<Q> fullJoin(EntityPath<?> target) {
        return new JoinOnStep<>(self(), JoinType.FULL, target);
    }

    public Q limit(int value) {
        if (value < 0) throw new IllegalArgumentException("limit cannot be negative");
        this.limit = value;
        return self();
    }

    public Q offset(long value) {
        if (value < 0) throw new IllegalArgumentException("offset cannot be negative");
        this.offset = value;
        return self();
    }

    public String sql() {
        return QueryExecution.planFor(this).sql();
    }

    public List<Object> parameters() {
        return SqlRenderer.collectParams(this);
    }

    final Q addJoin(JoinType type, EntityPath<?> target, Predicate on) {
        joins.add(new JoinSpec(type, target, on));
        return self();
    }

    @SuppressWarnings("unchecked")
    private Q self() {
        return (Q) this;
    }

    EntityPath<?> from() {
        return from;
    }

    Expression<?>[] projections() {
        return projections;
    }

    boolean selectEntity() {
        return selectEntity;
    }

    List<JoinSpec> joins() {
        return joins;
    }

    Predicate whereNode() {
        return where;
    }

    List<OrderSpecifier> orderByNodes() {
        return orderBy;
    }

    Integer limitValue() {
        return limit;
    }

    Long offsetValue() {
        return offset;
    }
}

