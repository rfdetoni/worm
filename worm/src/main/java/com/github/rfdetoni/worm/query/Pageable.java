package com.github.rfdetoni.worm.query;

import java.util.Objects;

/**
 * Represents pagination and sorting for a query.
 *
 * @param pageNumber zero-based page number
 * @param pageSize page size
 * @param sort sort instruction
 */
public record Pageable(int pageNumber, int pageSize, Sort sort) {

    public enum Direction {
        ASC, DESC
    }

    /**
     * Safe external sort instruction. Only property/column identifiers are accepted.
     * Trusted SQL expressions belong in {@link FilterBuilder#orderByRaw(String)}.
     */
    public record Sort(String property, Direction direction) {
        public Sort {
            Objects.requireNonNull(property, "Sort property must not be null.");
            property = property.trim();
            if (property.isEmpty()) {
                throw new IllegalArgumentException("Sort property must not be blank.");
            }
            for (int index = 0; index < property.length(); index++) {
                char character = property.charAt(index);
                if (Character.isLetterOrDigit(character) || character == '_' || character == '.') {
                    continue;
                }
                throw new IllegalArgumentException("Unsafe sort property: " + property);
            }
            direction = direction == null ? Direction.ASC : direction;
        }

        public static Sort by(String property, Direction direction) {
            return new Sort(property, direction);
        }

        public static Sort asc(String property) {
            return by(property, Direction.ASC);
        }

        public static Sort desc(String property) {
            return by(property, Direction.DESC);
        }
    }

    public static Pageable of(int pageNumber, int pageSize) {
        return of(pageNumber, pageSize, null);
    }

    public static Pageable of(int pageNumber, int pageSize, Sort sort) {
        int normalizedPage = Math.max(0, pageNumber);
        int normalizedSize = Math.max(1, pageSize);
        return new Pageable(normalizedPage, normalizedSize, sort == null ? Sort.asc("id") : sort);
    }

    public static Pageable ofSize(int pageSize) {
        return of(0, pageSize, null);
    }

    public Pageable {
        Objects.requireNonNull(sort, "Sort must not be null.");
    }

    public long getOffset() {
        return (long) pageNumber * pageSize;
    }
}
