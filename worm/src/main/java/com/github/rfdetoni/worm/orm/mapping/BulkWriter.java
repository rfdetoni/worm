package com.github.rfdetoni.worm.orm.mapping;

import com.github.rfdetoni.worm.orm.dialect.SqlDialect;
import com.github.rfdetoni.worm.orm.registry.EntityMetadata;

import java.util.List;

/**
 * SPI for driver-level bulk write operations.
 *
 * <p>Implementations may use the most efficient mechanism available for their target database
 * (e.g. PostgreSQL {@code COPY} for inserts, {@code unnest} arrays for updates/deletes,
 * multi-row {@code VALUES} for MySQL, etc.).  All methods return {@code null} to signal
 * "strategy not applicable" — the caller must then fall back to standard JDBC batch updates.
 *
 * <p>Implementations are obtained from {@link SqlDialect#createBulkWriter}
 * rather than being instantiated directly, so the ORM core has no compile-time dependency on
 * any specific driver class.
 */
public interface BulkWriter {

    /**
     * Bulk-inserts entities using the most efficient mechanism available.
     *
     * @param entities non-null, non-empty list of entities
     * @param meta     entity metadata
     * @param <T>      entity type
     * @return per-row affected counts, or {@code null} if this strategy is not applicable
     *         (e.g. list is below the threshold)
     */
    <T> int[] bulkInsert(List<T> entities, EntityMetadata<T> meta);

    /**
     * Bulk-updates entities.
     *
     * @return per-row affected counts, or {@code null} if not applicable
     */
    <T> int[] bulkUpdate(List<T> entities, EntityMetadata<T> meta);

    /**
     * Bulk-deletes entities by ID.
     *
     * @return per-row affected counts, or {@code null} if not applicable
     */
    <T> int[] bulkDelete(List<T> entities, EntityMetadata<T> meta);

    /**
     * Bulk-upserts entities (insert-or-update by primary key).
     *
     * @return per-row affected counts, or {@code null} if not applicable
     */
    <T> int[] bulkUpsert(List<T> entities, EntityMetadata<T> meta);
}

