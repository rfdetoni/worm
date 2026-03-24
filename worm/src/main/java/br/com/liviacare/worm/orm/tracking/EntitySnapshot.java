package br.com.liviacare.worm.orm.tracking;

import br.com.liviacare.worm.orm.registry.EntityMetadata;

import java.lang.invoke.MethodHandle;
import java.util.*;

/**
 * Immutable snapshot of column values captured from an entity instance.
 */
public final class EntitySnapshot {

    private final Map<String, Object> values;

    private EntitySnapshot(Map<String, Object> values) {
        // Collections.unmodifiableMap wraps without copying — safe because capture()
        // creates a fresh map and passes ownership here. This avoids the previous
        // double-allocation (capture's LinkedHashMap + new LinkedHashMap in constructor).
        // Map.copyOf() was not used because it rejects null column values.
        this.values = Collections.unmodifiableMap(values);
    }

    public static <T> EntitySnapshot capture(T entity, EntityMetadata<T> metadata) {
        Map<String, Object> snapshot = new LinkedHashMap<>(metadata.selectColumns().size());
        MethodHandle[] getters = metadata.selectGetters();
        List<String> columns = metadata.selectColumns();

        for (int i = 0; i < columns.size(); i++) {
            String column = columns.get(i);
            try {
                snapshot.put(column, getters[i].invoke(entity));
            } catch (Throwable t) {
                throw new IllegalStateException("Failed to capture snapshot for column '" + column + "'", t);
            }
        }

        return new EntitySnapshot(snapshot);
    }

    public Map<String, Object> values() {
        return values;
    }

    public <T> List<String> dirtyUpdatableColumns(T entity, EntityMetadata<T> metadata) {
        List<String> dirty = new ArrayList<>();
        MethodHandle[] getters = metadata.selectGetters();

        for (String column : metadata.updatableColumns()) {
            int idx = metadata.columnIndex(column);
            if (idx < 0) {
                continue;
            }
            try {
                Object current = getters[idx].invoke(entity);
                if (!Objects.equals(values.get(column), current)) {
                    dirty.add(column);
                }
            } catch (Throwable t) {
                throw new IllegalStateException("Failed to compare snapshot for column '" + column + "'", t);
            }
        }

        return dirty;
    }
}

