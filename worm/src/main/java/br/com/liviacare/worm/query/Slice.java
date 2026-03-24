package br.com.liviacare.worm.query;

import java.util.List;

/**
 * Represents a slice of data from a larger dataset.
 * Kept in the query package for compatibility with existing APIs.
 */
public record Slice<T>(
        List<T> content,
        Pageable pageable,
        boolean hasNext
) {
}
