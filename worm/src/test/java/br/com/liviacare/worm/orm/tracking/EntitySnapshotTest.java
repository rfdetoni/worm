package br.com.liviacare.worm.orm.tracking;

import br.com.liviacare.worm.ActiveRecord;
import br.com.liviacare.worm.annotation.mapping.DbId;
import br.com.liviacare.worm.annotation.mapping.DbTable;
import br.com.liviacare.worm.annotation.mapping.Track;
import br.com.liviacare.worm.orm.registry.EntityMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EntitySnapshotTest {

    @DbTable("snapshot_users")
    public static class SnapshotUser {
        @DbId("id")
        private Long id;
        private String name;
        private Integer score;

        public SnapshotUser() {
        }

        SnapshotUser(Long id, String name, Integer score) {
            this.id = id;
            this.name = name;
            this.score = score;
        }

        public void setName(String name) {
            this.name = name;
        }

        public void setScore(Integer score) {
            this.score = score;
        }
    }

    @Track
    @DbTable("tracked_snapshot_users")
    public static class TrackedSnapshotUser extends ActiveRecord<TrackedSnapshotUser, Long> {
        @DbId("id")
        private Long id;
        private String name;

        public TrackedSnapshotUser() {
        }

        TrackedSnapshotUser(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public Long getId() {
            return id;
        }
    }

    @Test
    void detectsOnlyChangedUpdatableColumns() {
        EntityMetadata<SnapshotUser> metadata = EntityMetadata.of(SnapshotUser.class);
        SnapshotUser user = new SnapshotUser(1L, "Alice", 10);

        EntitySnapshot snapshot = EntitySnapshot.capture(user, metadata);
        user.setName("Alice");
        user.setScore(11);

        List<String> dirty = snapshot.dirtyUpdatableColumns(user, metadata);
        assertEquals(List.of("score"), dirty);
    }

    @Test
    void trackAnnotationIsOptInAndActiveRecordCanStoreSnapshotInline() {
        EntityMetadata<SnapshotUser> regularMetadata = EntityMetadata.of(SnapshotUser.class);
        EntityMetadata<TrackedSnapshotUser> trackedMetadata = EntityMetadata.of(TrackedSnapshotUser.class);

        assertFalse(regularMetadata.isTracked());
        assertTrue(trackedMetadata.isTracked());

        TrackedSnapshotUser user = new TrackedSnapshotUser(7L, "Bob");
        EntitySnapshot snapshot = EntitySnapshot.capture(user, trackedMetadata);

        user.__wormSetSnapshot(snapshot);
        assertSame(snapshot, user.__wormSnapshot());

        user.__wormClearSnapshot();
        assertNull(user.__wormSnapshot());
    }

    @Test
    void snapshotSupportsNullColumnValues() {
        EntityMetadata<TrackedSnapshotUser> metadata = EntityMetadata.of(TrackedSnapshotUser.class);
        TrackedSnapshotUser user = new TrackedSnapshotUser(9L, null);

        EntitySnapshot snapshot = EntitySnapshot.capture(user, metadata);

        assertTrue(snapshot.values().containsKey("name"));
        assertNull(snapshot.values().get("name"));
    }
}

