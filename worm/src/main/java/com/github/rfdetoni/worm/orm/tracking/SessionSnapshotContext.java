package com.github.rfdetoni.worm.orm.tracking;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Session-scoped snapshot container designed for virtual-thread execution.
 *
 * <p>Scoped values are the primary storage mechanism. A ThreadLocal fallback is retained only
 * for backwards compatibility with callers that operate outside an explicit WORM scope; it is
 * created lazily and removed as soon as it becomes empty to avoid retaining entity graphs on
 * pooled platform threads.</p>
 */
public final class SessionSnapshotContext {

    private static final ScopedValue<Map<Object, EntitySnapshot>> SCOPED = ScopedValue.newInstance();
    private static final ThreadLocal<Map<Object, EntitySnapshot>> FALLBACK = new ThreadLocal<>();

    private SessionSnapshotContext() {
    }

    public static boolean isBound() {
        return SCOPED.isBound();
    }

    public static <T> T runInScope(Supplier<T> action) {
        return ScopedValue.where(SCOPED, new IdentityHashMap<>()).call(action::get);
    }

    public static void runInScope(Runnable action) {
        ScopedValue.where(SCOPED, new IdentityHashMap<>()).run(action);
    }

    public static EntitySnapshot get(Object entity) {
        Map<Object, EntitySnapshot> snapshots = currentMap(false);
        return snapshots == null ? null : snapshots.get(entity);
    }

    public static void put(Object entity, EntitySnapshot snapshot) {
        currentMap(true).put(entity, snapshot);
    }

    public static void putAll(Map<Object, EntitySnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }
        currentMap(true).putAll(snapshots);
    }

    public static void remove(Object entity) {
        Map<Object, EntitySnapshot> snapshots = currentMap(false);
        if (snapshots == null) {
            return;
        }
        snapshots.remove(entity);
        if (!SCOPED.isBound() && snapshots.isEmpty()) {
            FALLBACK.remove();
        }
    }

    /**
     * Clears only the compatibility fallback for the current thread.
     * Scoped snapshots are owned by their ScopedValue scope and require no manual cleanup.
     */
    public static void clearFallback() {
        FALLBACK.remove();
    }

    private static Map<Object, EntitySnapshot> currentMap(boolean create) {
        if (SCOPED.isBound()) {
            return SCOPED.get();
        }
        Map<Object, EntitySnapshot> snapshots = FALLBACK.get();
        if (snapshots == null && create) {
            snapshots = new IdentityHashMap<>();
            FALLBACK.set(snapshots);
        }
        return snapshots;
    }
}
