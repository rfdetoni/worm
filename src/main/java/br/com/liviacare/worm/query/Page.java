package br.com.liviacare.worm.query;

import java.util.List;

/**
 * Page record for the query package. Contains pageable metadata and totals.
 */
public record Page<T>(
        List<T> content,
        Pageable pageable,
        boolean hasNext,
        long totalElements,
        int totalPages
) {
}
