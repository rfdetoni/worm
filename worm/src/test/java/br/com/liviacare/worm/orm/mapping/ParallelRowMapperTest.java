package br.com.liviacare.worm.orm.mapping;

import br.com.liviacare.worm.annotation.mapping.DbColumn;
import br.com.liviacare.worm.annotation.mapping.DbId;
import br.com.liviacare.worm.annotation.mapping.DbJoin;
import br.com.liviacare.worm.annotation.mapping.DbTable;
import br.com.liviacare.worm.orm.registry.EntityMetadata;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
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

    @DbTable("contacts")
    record Contact(
            @DbId("id") Long id,
            @DbColumn("user_id") Long userId,
            @DbColumn("email") String email
    ) {
    }

    @DbTable("users")
    record UserWithContacts(
            @DbId("id") Long id,
            @DbColumn("name") String name,
            @DbJoin(mappedBy = "user_id") List<Contact> contacts
    ) {
    }

    // ── Phase 1 ──────────────────────────────────────────────────────────────

    @Test
    void extractRawCapturesAllColumns() throws SQLException {
        EntityMetadata<Product> meta = EntityMetadata.of(Product.class);
        ResultSet rs = mockProductRow(7L, "Widget", 9.99);

        Object[] raw = EntityMapper.extractRaw(rs, meta);

        assertEquals(3, raw.length);
        assertEquals(7L, raw[0]);
        assertEquals("Widget", raw[1]);
        assertEquals(9.99, raw[2]);
    }

    @Test
    void extractRawHandlesNullColumnValues() throws SQLException {
        EntityMetadata<Product> meta = EntityMetadata.of(Product.class);
        ResultSet rs = mockProductRow(1L, null, null);

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

        ResultSet rs = mockProductRow(5L, "Donut", 1.25);

        // Single-phase reference result
        Product singlePhase = EntityMapper.mapRow(rs, meta);

        // Two-phase result
        // (rs is already consumed; create a fresh row mock for phase 1)
        rs = mockProductRow(5L, "Donut", 1.25);
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

    @Test
    void mergeCollectionJoinsMergesConsecutiveRowsInSinglePass() {
        EntityMetadata<UserWithContacts> meta = EntityMetadata.of(UserWithContacts.class);

        List<UserWithContacts> rawRows = List.of(
                new UserWithContacts(1L, "Ana", List.of(new Contact(100L, 1L, "ana-a@x.com"))),
                new UserWithContacts(1L, "Ana", List.of(new Contact(101L, 1L, "ana-b@x.com"))),
                new UserWithContacts(2L, "Bob", List.of())
        );

        List<UserWithContacts> merged = EntityMapper.mergeCollectionJoins(rawRows, meta);

        assertEquals(2, merged.size());
        assertEquals(1L, merged.get(0).id());
        assertEquals(2, merged.get(0).contacts().size());
        assertEquals(100L, merged.get(0).contacts().get(0).id());
        assertEquals(101L, merged.get(0).contacts().get(1).id());

        assertEquals(2L, merged.get(1).id());
        assertTrue(merged.get(1).contacts().isEmpty());
    }

    @Test
    void mergeCollectionJoinsMergesOutOfOrderRowsById() {
        EntityMetadata<UserWithContacts> meta = EntityMetadata.of(UserWithContacts.class);

        List<UserWithContacts> rawRows = List.of(
                new UserWithContacts(1L, "Ana", List.of(new Contact(100L, 1L, "ana-a@x.com"))),
                new UserWithContacts(2L, "Bob", List.of(new Contact(200L, 2L, "bob-a@x.com"))),
                new UserWithContacts(1L, "Ana", List.of(new Contact(101L, 1L, "ana-b@x.com")))
        );

        List<UserWithContacts> merged = EntityMapper.mergeCollectionJoins(rawRows, meta);

        assertEquals(2, merged.size());
        assertEquals(1L, merged.get(0).id());
        assertEquals(2, merged.get(0).contacts().size());
        assertEquals(100L, merged.get(0).contacts().get(0).id());
        assertEquals(101L, merged.get(0).contacts().get(1).id());

        assertEquals(2L, merged.get(1).id());
        assertEquals(1, merged.get(1).contacts().size());
        assertEquals(200L, merged.get(1).contacts().get(0).id());
    }

    private static ResultSet mockProductRow(Long id, String name, Double price) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(md);
        when(md.getColumnCount()).thenReturn(3);
        when(md.getColumnLabel(1)).thenReturn("id");
        when(md.getColumnLabel(2)).thenReturn("name");
        when(md.getColumnLabel(3)).thenReturn("price");
        when(rs.getObject(1)).thenReturn(id);
        when(rs.getObject(2)).thenReturn(name);
        when(rs.getObject(3)).thenReturn(price);
        return rs;
    }
}

