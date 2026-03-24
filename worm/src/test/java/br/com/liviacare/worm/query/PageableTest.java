package br.com.liviacare.worm.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PageableTest {

    @Test
    void of_normalizesNegativePageNumber() {
        Pageable p = Pageable.of(-1, 10);
        assertEquals(0, p.pageNumber());
    }

    @Test
    void of_normalizesZeroPageSize() {
        Pageable p = Pageable.of(0, 0);
        assertEquals(1, p.pageSize());
    }

    @Test
    void of_defaultsSortToIdAsc() {
        Pageable p = Pageable.of(0, 20);
        assertNotNull(p.sort());
        assertEquals("id", p.sort().property());
        assertEquals(Pageable.Direction.ASC, p.sort().direction());
    }

    @Test
    void ofSize_firstPage() {
        Pageable p = Pageable.ofSize(15);
        assertEquals(0, p.pageNumber());
        assertEquals(15, p.pageSize());
    }

    @Test
    void getOffset_calculatedCorrectly() {
        Pageable p = Pageable.of(3, 10);
        assertEquals(30L, p.getOffset());
    }

    @Test
    void getOffset_firstPageIsZero() {
        assertEquals(0L, Pageable.of(0, 25).getOffset());
    }

    @Test
    void sortAsc_desc_factories() {
        Pageable.Sort asc = Pageable.Sort.asc("name");
        assertEquals(Pageable.Direction.ASC, asc.direction());

        Pageable.Sort desc = Pageable.Sort.desc("createdAt");
        assertEquals(Pageable.Direction.DESC, desc.direction());
    }

    @Test
    void of_withCustomSort() {
        Pageable p = Pageable.of(1, 5, Pageable.Sort.desc("name"));
        assertEquals("name", p.sort().property());
        assertEquals(Pageable.Direction.DESC, p.sort().direction());
    }

    @Test
    void canonicalConstructor_requiresNonNullSort() {
        assertThrows(NullPointerException.class, () -> new Pageable(0, 10, null));
    }
}

