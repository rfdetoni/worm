package com.github.rfdetoni.worm.orm.registry;

import com.github.rfdetoni.worm.query.FilterBuilder;

/**
 * Typed column descriptor for compile-time-safe use in {@link FilterBuilder}.
 *
 * <p>Generated {@code {Entity}_.java} metamodel classes expose one {@code WormAttribute}
 * constant per mapped column so that filter predicates like
 * {@code filter.eq(User_.firstName, "Alice")} are validated at compile time instead of
 * relying on raw string literals.
 *
 * <p>The type parameters provide IDE auto-complete and catch type mismatches at compile
 * time when the typed {@link FilterBuilder} overloads are used.
 *
 * @param <E> the owning entity type (e.g. {@code User})
 * @param <V> the Java type of the column value (e.g. {@code String}, {@code UUID})
 */
public record WormAttribute<E, V>(
        /** SQL column name as it appears in the database, e.g. {@code "first_name"}. */
        String columnName,
        /** Java type of the column value, e.g. {@code String.class}. */
        Class<V> type
) {
    /**
     * Convenience factory — avoids raw-type warnings in generated code.
     *
     * @param columnName DB column name
     * @param type       Java value type
     * @param <E>        owning entity
     * @param <V>        value type
     * @return new attribute descriptor
     */
    public static <E, V> WormAttribute<E, V> of(String columnName, Class<V> type) {
        return new WormAttribute<>(columnName, type);
    }
}

