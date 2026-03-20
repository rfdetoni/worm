package br.com.liviacare.worm.orm.mapping;

import br.com.liviacare.worm.annotation.mapping.DbColumn;
import br.com.liviacare.worm.annotation.mapping.DbId;
import br.com.liviacare.worm.annotation.mapping.DbTable;
import br.com.liviacare.worm.orm.registry.EntityMetadata;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Validates the two-phase parallel mapping pipeline introduced in EntityMapper:
 * <ol>
 *   <li>Phase 1 – {@link EntityMapper#extractRaw}: serial JDBC read → {@code Object[]}</li>
 *   <li>Phase 2 – {@link EntityMapper#mapFromRaw}: CPU-only entity construction</li>
 * </ol>
 *
 * <p>The tests confirm that both phases together produce results identical to the
 * single-phase {@link EntityMapper#mapRow} path, and that parallel execution via
 * {@code parallelStream()} preserves order and produces no data corruption.
 */
class ParallelRowMapperTest {

    @DbTable("products")
    record Product(
            @DbId("id") Long id,
            @DbColumn("name") String name,
            @DbColumn("price") Double price
    ) {
    }

    // ── Phase 1 ──────────────────────────────────────────────────────────────

    @Test
    void extractRawCapturesAllColumns() throws SQLException {
        EntityMetadata<Product> meta = EntityMetadata.of(Product.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id")).thenReturn(7L);
        when(rs.getObject("name")).thenReturn("Widget");
        when(rs.getObject("price")).thenReturn(9.99);

        Object[] raw = EntityMapper.extractRaw(rs, meta);

        assertEquals(3, raw.length);
        assertEquals(7L, raw[0]);
        assertEquals("Widget", raw[1]);
        assertEquals(9.99, raw[2]);
    }

    @Test
    void extractRawHandlesNullColumnValues() throws SQLException {
        EntityMetadata<Product> meta = EntityMetadata.of(Product.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id")).thenReturn(1L);
        when(rs.getObject("name")).thenReturn(null);   // nullable column
        when(rs.getObject("price")).thenReturn(null);

        Object[] raw = EntityMapper.extractRaw(rs, meta);

        assertEquals(1L, raw[0]);
        assertNull(raw[1]);
        assertNull(raw[2]);
    }

    // ── Phase 2 ──────────────────────────────────────────────────────────────

    @Test
    void mapFromRawBuildsEntityCorrectly() throws Throwable {
        EntityMetadata<Product> meta = EntityMetadata.of(Product.class);
        Object[] raw = {42L, "Gadget", 19.95};

        Product product = EntityMapper.mapFromRaw(raw, meta);

        assertEquals(42L, product.id());
        assertEquals("Gadget", product.name());
        assertEquals(19.95, product.price());
    }

    @Test
    void mapFromRawHandlesNullValues() throws Throwable {
        EntityMetadata<Product> meta = EntityMetadata.of(Product.class);
        Object[] raw = {99L, null, null};

        Product product = EntityMapper.mapFromRaw(raw, meta);

        assertEquals(99L, product.id());
        assertNull(product.name());
        assertNull(product.price());
    }

    // ── Combined pipeline: extractRaw → mapFromRaw ≡ mapRow ─────────────────

    @Test
    void twoPhaseProducesIdenticalResultToMapRow() throws Throwable {
        EntityMetadata<Product> meta = EntityMetadata.of(Product.class);

        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id")).thenReturn(5L);
        when(rs.getObject("name")).thenReturn("Donut");
        when(rs.getObject("price")).thenReturn(1.25);

        // Single-phase reference result
        Product singlePhase = EntityMapper.mapRow(rs, meta);

        // Two-phase result
        // (rs is already consumed; re-stub for phase 1)
        when(rs.getObject("id")).thenReturn(5L);
        when(rs.getObject("name")).thenReturn("Donut");
        when(rs.getObject("price")).thenReturn(1.25);
        Object[] raw = EntityMapper.extractRaw(rs, meta);
        Product twoPhase = EntityMapper.mapFromRaw(raw, meta);

        assertEquals(singlePhase, twoPhase);
    }

    // ── Parallel correctness: order and completeness ──────────────────────────

    @Test
    void parallelStreamPreservesOrder() throws Throwable {
        EntityMetadata<Product> meta = EntityMetadata.of(Product.class);

        // Simulate 200 "pre-read" raw rows (Phase 1 result)
        List<Object[]> rawRows = new ArrayList<>(200);
        for (int i = 0; i < 200; i++) {
            rawRows.add(new Object[]{(long) i, "Product-" + i, i * 0.5});
        }

        // Phase 2 via parallelStream with ordered collector
        List<Product> products = rawRows.parallelStream()
                .map(raw -> {
                    try {
                        return EntityMapper.mapFromRaw(raw, meta);
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(java.util.stream.Collectors.toList());

        assertEquals(200, products.size());
        IntStream.range(0, 200).forEach(i -> {
            assertEquals((long) i, products.get(i).id(),
                    "Row at index " + i + " must retain its original position after parallel mapping");
            assertEquals("Product-" + i, products.get(i).name());
        });
    }

    @Test
    void emptyRawRowListProducesEmptyResult() {
        List<Object[]> rawRows = List.of();
        EntityMetadata<Product> meta = EntityMetadata.of(Product.class);

        List<Product> products = rawRows.parallelStream()
                .map(raw -> {
                    try {
                        return EntityMapper.mapFromRaw(raw, meta);
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(java.util.stream.Collectors.toList());

        assertTrue(products.isEmpty());
    }
}

