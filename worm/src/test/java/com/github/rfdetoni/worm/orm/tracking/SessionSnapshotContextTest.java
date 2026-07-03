package com.github.rfdetoni.worm.orm.tracking;

import com.github.rfdetoni.worm.annotation.mapping.DbId;
import com.github.rfdetoni.worm.annotation.mapping.DbTable;
import com.github.rfdetoni.worm.orm.registry.EntityMetadata;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionSnapshotContextTest {

    @DbTable("session_snapshot_users")
    static class SessionUser {
        @DbId("id")
        private Long id;
        private String name;

        SessionUser() {
        }

        SessionUser(Long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    @Test
    void scopedSnapshotsAreIsolatedPerScope() {
        EntityMetadata<SessionUser> metadata = EntityMetadata.of(SessionUser.class);
        SessionUser user = new SessionUser(1L, "A");
        EntitySnapshot snapshot = EntitySnapshot.capture(user, metadata);

        SessionSnapshotContext.runInScope(() -> {
            SessionSnapshotContext.put(user, snapshot);
            assertSame(snapshot, SessionSnapshotContext.get(user));
        });

        assertNull(SessionSnapshotContext.get(user));
    }

    @Test
    void fallbackContextRemainsAvailableOutsideExplicitScope() {
        EntityMetadata<SessionUser> metadata = EntityMetadata.of(SessionUser.class);
        SessionUser user = new SessionUser(2L, "B");
        EntitySnapshot snapshot = EntitySnapshot.capture(user, metadata);

        SessionSnapshotContext.put(user, snapshot);
        assertNotNull(SessionSnapshotContext.get(user));
        SessionSnapshotContext.remove(user);
        assertNull(SessionSnapshotContext.get(user));
    }
}

