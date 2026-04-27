package br.com.liviacare.worm.dsl;

import java.time.temporal.Temporal;
import java.util.Objects;

/**
 * Compile-time generated entity path base.
 *
 * @param <T> entity type
 */
public abstract class EntityPath<T> implements Expression<T> {

    private final Class<T> entityType;
    private final String tableName;
    private final String alias;
    private final int shapeHash;

    protected EntityPath(Class<T> entityType, String tableName, String alias) {
        this.entityType = Objects.requireNonNull(entityType, "entityType cannot be null");
        this.tableName = Objects.requireNonNull(tableName, "tableName cannot be null");
        this.alias = Objects.requireNonNull(alias, "alias cannot be null");
        int h = 13;
        h = 31 * h + entityType.getName().hashCode();
        h = 31 * h + tableName.hashCode();
        h = 31 * h + alias.hashCode();
        this.shapeHash = h;
    }

    @Override
    public final Class<T> type() {
        return entityType;
    }

    public final Class<T> entityType() {
        return entityType;
    }

    public final String tableName() {
        return tableName;
    }

    public final String alias() {
        return alias;
    }

    @Override
    public final int shapeHash() {
        return shapeHash;
    }

    protected final StringPath string(String column) {
        return new StringPath(this, column);
    }

    protected final BooleanPath bool(String column) {
        return new BooleanPath(this, column);
    }

    protected final UuidPath uuid(String column) {
        return new UuidPath(this, column);
    }

    protected final <N extends Number & Comparable<? super N>> NumberPath<N> number(String column, Class<N> type) {
        return new NumberPath<>(this, column, type);
    }

    protected final <E extends Enum<E>> EnumPath<E> enumeration(String column, Class<E> type) {
        return new EnumPath<>(this, column, type);
    }

    protected final <TValue extends Comparable<? super TValue>> ComparablePath<TValue> comparable(String column, Class<TValue> type) {
        return new ComparablePath<>(this, column, type);
    }

    protected final <TValue extends Temporal & Comparable<? super TValue>> DatePath<TValue> date(String column, Class<TValue> type) {
        return new DatePath<>(this, column, type);
    }

    protected final <TValue extends Temporal & Comparable<? super TValue>> DateTimePath<TValue> dateTime(String column, Class<TValue> type) {
        return new DateTimePath<>(this, column, type);
    }
}

