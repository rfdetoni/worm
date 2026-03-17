package br.com.liviacare.worm.query;

import java.util.List;

/**
 * Page record compatível com the query package. Contains pageable info and totals.
 */
public record Page<T>(
        List<T> content,
        Pageable pageable,
        boolean hasNext,
        long totalElements,
        int totalPages
) {
}
