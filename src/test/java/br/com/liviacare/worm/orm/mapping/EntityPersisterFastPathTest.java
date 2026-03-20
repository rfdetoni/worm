package br.com.liviacare.worm.orm.mapping;

import br.com.liviacare.worm.annotation.mapping.DbColumn;
import br.com.liviacare.worm.annotation.mapping.DbId;
import br.com.liviacare.worm.annotation.mapping.DbTable;
import br.com.liviacare.worm.annotation.mapping.DbVersion;
import br.com.liviacare.worm.orm.registry.EntityMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityPersisterFastPathTest {

    enum Status { ACTIVE, INACTIVE }

    @DbTable("sample_entities")
    record SampleEntity(
            @DbId("id") Long id,
            String name,
            @DbColumn("status") Status status,
            @DbVersion Integer version
    ) {
    }

    @BeforeEach
    void clearCache() {
        FastPathDecisionCache.clear();
    }

    @Test
    void arrayVariantsMatchListVariants() {
        EntityMetadata<SampleEntity> metadata = EntityMetadata.of(SampleEntity.class);
        SampleEntity entity = new SampleEntity(10L, "Alice", Status.ACTIVE, 3);

        assertArrayEquals(
                EntityPersister.insertValues(entity, metadata).toArray(),
                EntityPersister.insertValuesArray(entity, metadata)
        );
        assertArrayEquals(
                EntityPersister.updateValues(entity, metadata, entity.id()).toArray(),
                EntityPersister.updateValuesArray(entity, metadata, entity.id())
        );
    }

    @Test
    void fastPathDelegatesConsistentlyAndCachesDecision() {
        EntityMetadata<SampleEntity> metadata = EntityMetadata.of(SampleEntity.class);
        SampleEntity entity = new SampleEntity(11L, "Bob", Status.INACTIVE, 7);

        assertTrue(FastPathDecisionCache.canUseFastPath(SampleEntity.class, metadata));
        assertEquals(1, FastPathDecisionCache.size());

        assertArrayEquals(
                EntityPersister.insertValuesArray(entity, metadata),
                EntityPersisterFastPath.insertValuesArrayFast(entity, metadata)
        );
        assertArrayEquals(
                EntityPersister.updateValuesArray(entity, metadata, entity.id()),
                EntityPersisterFastPath.updateValuesArrayFast(entity, metadata, entity.id())
        );
        assertEquals(
                EntityPersister.insertValues(entity, metadata),
                EntityPersisterFastPath.insertValuesFast(entity, metadata)
        );
        assertEquals(
                EntityPersister.updateValues(entity, metadata, entity.id()),
                EntityPersisterFastPath.updateValuesFast(entity, metadata, entity.id())
        );
    }
}

