package br.com.liviacare.worm.orm.tracking;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Session-scoped snapshot container designed for virtual-thread execution.
 *
 * <p>When running inside a scoped session, snapshots are isolated to that scope.
 * Outside an explicit scope, a per-thread map is used as a compatibility fallback.
 */
public final class SessionSnapshotContext {

    private static final ScopedValue<Map<Object, EntitySnapshot>> SCOPED = ScopedValue.newInstance();
    private static final ThreadLocal<Map<Object, EntitySnapshot>> FALLBACK = ThreadLocal.withInitial(IdentityHashMap::new);

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
        return currentMap().get(entity);
    }

    public static void put(Object entity, EntitySnapshot snapshot) {
        currentMap().put(entity, snapshot);
    }

    public static void putAll(Map<Object, EntitySnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }
        currentMap().putAll(snapshots);
    }

    public static void remove(Object entity) {
        currentMap().remove(entity);
    }

    private static Map<Object, EntitySnapshot> currentMap() {
        return SCOPED.isBound() ? SCOPED.get() : FALLBACK.get();
    }
}

