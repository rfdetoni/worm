package br.com.liviacare.worm.dsl;

import java.util.Objects;

public final class StringPath extends ComparablePath<String> {

    public StringPath(EntityPath<?> root, String column) {
        super(root, column, String.class);
    }

    public Predicate like(String pattern) {
        return new LikePredicate(this, LikeMode.RAW, new ValueExpression<>(Objects.requireNonNull(pattern, "pattern cannot be null"), String.class));
    }

    public Predicate contains(String value) {
        return new LikePredicate(this, LikeMode.CONTAINS, new ValueExpression<>(Objects.requireNonNull(value, "value cannot be null"), String.class));
    }

    public Predicate startsWith(String value) {
        return new LikePredicate(this, LikeMode.STARTS_WITH, new ValueExpression<>(Objects.requireNonNull(value, "value cannot be null"), String.class));
    }

    public Predicate endsWith(String value) {
        return new LikePredicate(this, LikeMode.ENDS_WITH, new ValueExpression<>(Objects.requireNonNull(value, "value cannot be null"), String.class));
    }
}

