package br.com.liviacare.worm.orm.mapping;

import br.com.liviacare.worm.annotation.audit.Active;
import br.com.liviacare.worm.annotation.mapping.DbColumn;
import br.com.liviacare.worm.annotation.mapping.DbId;
import br.com.liviacare.worm.annotation.mapping.DbTable;
import br.com.liviacare.worm.annotation.mapping.DbVersion;
import br.com.liviacare.worm.orm.registry.EntityMetadata;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class PostgresBulkWriterTest {

    @DbTable("bulk_users")
    record BulkUser(
            @DbId("id") Long id,
            String name,
            @DbColumn("score") Integer score
    ) {
    }

    @DbTable("versioned_users")
    record VersionedBulkUser(
            @DbId("id") Long id,
            String name,
            @DbVersion Integer version
    ) {
    }

    @DbTable("soft_users")
    record SoftDeleteUser(
            @DbId("id") Long id,
            String name,
            @Active @DbColumn("active") Boolean active
    ) {
    }

    @Test
    void returnsNullBelowThresholdWithoutTouchingDatabase() {
        PostgresBulkWriter writer = new PostgresBulkWriter(mock(DataSource.class), 10, 10);
        EntityMetadata<BulkUser> metadata = EntityMetadata.of(BulkUser.class);
        List<BulkUser> entities = List.of(new BulkUser(1L, "Alice", 5));

        assertNull(writer.copyInsert(entities, metadata));
        assertNull(writer.unnestUpdate(entities, metadata));
        assertNull(writer.unnestDelete(entities, metadata));
    }

    @Test
    void skipsUnnestUpdateForVersionedEntities() {
        PostgresBulkWriter writer = new PostgresBulkWriter(mock(DataSource.class), 1, 1);
        EntityMetadata<VersionedBulkUser> metadata = EntityMetadata.of(VersionedBulkUser.class);

        assertNull(writer.unnestUpdate(List.of(new VersionedBulkUser(1L, "Bob", 2)), metadata));
    }

    @Test
    void skipsUnnestDeleteForSoftDeleteEntities() {
        PostgresBulkWriter writer = new PostgresBulkWriter(mock(DataSource.class), 1, 1);
        EntityMetadata<SoftDeleteUser> metadata = EntityMetadata.of(SoftDeleteUser.class);

        assertNull(writer.unnestDelete(List.of(new SoftDeleteUser(1L, "Carol", true)), metadata));
    }
}

