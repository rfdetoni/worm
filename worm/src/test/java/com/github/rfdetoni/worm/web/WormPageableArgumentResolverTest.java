package com.github.rfdetoni.worm.web;

import com.github.rfdetoni.worm.query.Pageable;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WormPageableArgumentResolverTest {

    @Test
    void clampsOversizedHttpPage() {
        Pageable pageable = resolve("3", "10000", "createdAt,desc");

        assertEquals(3, pageable.pageNumber());
        assertEquals(200, pageable.pageSize());
        assertEquals("createdAt", pageable.sort().property());
        assertEquals(Pageable.Direction.DESC, pageable.sort().direction());
    }

    @Test
    void normalizesInvalidNumericValues() {
        Pageable pageable = resolve("not-a-number", "0", null);

        assertEquals(0, pageable.pageNumber());
        assertEquals(1, pageable.pageSize());
        assertEquals("id", pageable.sort().property());
    }

    @Test
    void invalidDirectionFallsBackToAscending() {
        Pageable pageable = resolve("0", "20", "name,sideways");

        assertEquals(Pageable.Direction.ASC, pageable.sort().direction());
    }

    @Test
    void rejectsUnsafeSortExpressionAtHttpBoundary() {
        assertThrows(IllegalArgumentException.class,
                () -> resolve("0", "20", "lower(name),asc"));
    }

    @Test
    void supportsCustomMaximumPageSize() {
        WormPageableArgumentResolver resolver = new WormPageableArgumentResolver(50);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("size", "75");

        Pageable pageable = (Pageable) resolver.resolveArgument(
                null, null, new ServletWebRequest(request), null);

        assertEquals(50, pageable.pageSize());
    }

    private Pageable resolve(String page, String size, String sort) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (page != null) request.setParameter("page", page);
        if (size != null) request.setParameter("size", size);
        if (sort != null) request.setParameter("sort", sort);

        WormPageableArgumentResolver resolver = new WormPageableArgumentResolver();
        return (Pageable) resolver.resolveArgument(
                null, null, new ServletWebRequest(request), null);
    }
}
