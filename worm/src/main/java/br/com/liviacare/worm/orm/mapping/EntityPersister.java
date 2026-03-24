package br.com.liviacare.worm.orm.mapping;

import br.com.liviacare.worm.orm.registry.EntityMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.postgresql.util.PGobject;

import java.lang.invoke.MethodHandle;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds bind-parameter lists for INSERT and UPDATE using pre-cached MethodHandles
 * from EntityMetadata. Does not mutate the entity; audit values are injected
 * as Instant.now() into the parameter list where configured.
 */
public final class EntityPersister {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private EntityPersister() {}

    /**
     * Returns the bind-parameter list for an INSERT statement, in the
     * same column order as {@link EntityMetadata#insertableColumns()}.
     */
    public static <T> List<Object> insertValues(T entity, EntityMetadata<T> metadata) {
        Object[] buffer = new Object[metadata.insertableColumns().size()];
        int count = fillInsertBuffer(entity, metadata, buffer);
        final List<Object> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(buffer[i]);
        }
        return values;
    }

    /**
     * Fills a pre-allocated buffer with INSERT parameters.
     * @param entity The entity to read from.
     * @param metadata The entity's metadata.
     * @param buffer The buffer to fill.
     */
    public static <T> void fillInsertParams(T entity, EntityMetadata<T> metadata, Object[] buffer) {
        fillInsertBuffer(entity, metadata, buffer);
    }

    public static <T> int fillInsertBuffer(T entity, EntityMetadata<T> metadata, Object[] buffer) {
        final Instant now = Instant.now();
        final String idCol = metadata.idColumnName();
        final Optional<String> createdAtCol = metadata.createdAtColumn();
        final Optional<String> updatedAtCol = metadata.updatedAtColumn();
        final boolean hasActive = metadata.hasActive();
        final String activeColumn = metadata.activeColumn();
        int out = 0;
        for (String column : metadata.insertableColumns()) {
            if (column.equals(idCol)) {
                try {
                    // PERF: fill caller-owned buffer in place to avoid per-entity array allocation.
                    buffer[out++] = metadata.idGetter().invoke(entity);
                } catch (Throwable e) {
                    throw new IllegalStateException("Failed to read ID column '" + column + "' from entity", e);
                }
                continue;
            }

            if (createdAtCol.isPresent() && createdAtCol.get().equals(column)) {
                buffer[out++] = mapAuditValue(now, metadata, column);
                continue;
            }
            if (updatedAtCol.isPresent() && updatedAtCol.get().equals(column)) {
                buffer[out++] = mapAuditValue(now, metadata, column);
                continue;
            }

            final int idx = metadata.columnIndex(column);
            final MethodHandle getter = metadata.selectGetters()[idx];
            try {
                Object val = getter.invoke(entity);
                if (hasActive && activeColumn.equals(column)) {
                    if (val == null || metadata.selectTypes()[idx] == boolean.class) {
                        val = metadata.activeDefaultValue();
                    }
                }
                buffer[out++] = prepareValue(val, column, metadata, idx);
            } catch (Throwable e) {
                throw new IllegalStateException("Failed to read column '" + column + "' from entity", e);
            }
        }
        return out;
    }

    /**
     * Returns the bind-parameter array for an INSERT statement, in the
     * same column order as {@link EntityMetadata#insertableColumns()}.
     */
    public static <T> Object[] insertValuesArray(T entity, EntityMetadata<T> metadata) {
        final Object[] values = new Object[metadata.insertableColumns().size()];
        fillInsertBuffer(entity, metadata, values);
        return values;
    }

    /**
     * Returns the bind-parameter list for an UPDATE statement, in the
     * same column order as {@link EntityMetadata#updatableColumns()},
     * followed by the entity's ID as the final bind value.
     */
    public static <T> List<Object> updateValues(T entity, EntityMetadata<T> metadata, Object id) {
        Object[] buffer = new Object[metadata.updatableColumns().size() + 1 + (metadata.hasVersion() ? 1 : 0)];
        int count = fillUpdateBuffer(entity, metadata, id, buffer);
        final List<Object> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(buffer[i]);
        }
        return values;
    }

    public static <T> int fillUpdateBuffer(T entity, EntityMetadata<T> metadata, Object id, Object[] buffer) {
        final Instant now = Instant.now();
        final Optional<String> updatedAtCol = metadata.updatedAtColumn();
        final boolean hasUpdatedAt = updatedAtCol.isPresent();
        int out = 0;
        for (String column : metadata.updatableColumns()) {
            if (hasUpdatedAt && updatedAtCol.get().equals(column)) {
                // PERF: write update parameters into caller-owned buffer for batch reuse.
                buffer[out++] = mapAuditValue(now, metadata, column);
                continue;
            }
            final int idx = metadata.columnIndex(column);
            final MethodHandle getter = metadata.selectGetters()[idx];
            try {
                Object val = getter.invoke(entity);
                buffer[out++] = prepareValue(val, column, metadata, idx);
            } catch (Throwable e) {
                throw new IllegalStateException("Failed to read column '" + column + "' from entity", e);
            }
        }
        buffer[out++] = id;
        if (metadata.hasVersion()) {
            try {
                buffer[out++] = metadata.versionGetter().invoke(entity);
            } catch (Throwable e) {
                throw new IllegalStateException("Failed to read version value from entity", e);
            }
        }
        return out;
    }

    /**
     * Returns the bind-parameter array for an UPDATE statement, in the
     * same column order as {@link EntityMetadata#updatableColumns()},
     * followed by the entity's ID and optional version bind values.
     */
    public static <T> Object[] updateValuesArray(T entity, EntityMetadata<T> metadata, Object id) {
        final Object[] values = new Object[metadata.updatableColumns().size() + 1 + (metadata.hasVersion() ? 1 : 0)];
        fillUpdateBuffer(entity, metadata, id, values);
        return values;
    }

    /**
     * Returns bind values for a partial UPDATE where only selected columns are emitted
     * in the SQL SET clause. The ID is always appended at the end for WHERE id = ?,
     * and version is appended when optimistic locking is enabled.
     */
    public static <T> List<Object> updateValuesForColumns(T entity, EntityMetadata<T> metadata, Object id, List<String> columns) {
        final List<Object> values = new ArrayList<>(columns.size() + 1 + (metadata.hasVersion() ? 1 : 0));
        final Instant now = Instant.now();
        final Optional<String> updatedAtCol = metadata.updatedAtColumn();

        for (String column : columns) {
            if (updatedAtCol.isPresent() && updatedAtCol.get().equals(column)) {
                values.add(mapAuditValue(now, metadata, column));
                continue;
            }

            final int idx = metadata.columnIndex(column);
            if (idx < 0) {
                throw new IllegalArgumentException("Unknown column for partial update: " + column);
            }

            final MethodHandle getter = metadata.selectGetters()[idx];
            try {
                Object val = getter.invoke(entity);
                values.add(prepareValue(val, column, metadata, idx));
            } catch (Throwable e) {
                throw new IllegalStateException("Failed to read column '" + column + "' from entity", e);
            }
        }

        values.add(id);
        if (metadata.hasVersion()) {
            try {
                values.add(metadata.versionGetter().invoke(entity));
            } catch (Throwable e) {
                throw new IllegalStateException("Failed to read version value from entity", e);
            }
        }
        return values;
    }

    private static Object prepareValue(Object val, String column, EntityMetadata<?> metadata, int idx) {
        if (val == null) return null;
        Class<?> type = metadata.selectTypes()[idx];
        
        if (type.isEnum()) {
            return ((Enum<?>) val).name();
        }

        if (metadata.isJsonColumn(column) || isJsonCandidate(type)) {
            try {
                PGobject pg = new PGobject();
                pg.setType("jsonb");
                pg.setValue(MAPPER.writeValueAsString(val));
                return pg;
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize field to JSONB", e);
            }
        }
        return val;
    }

    private static Object mapAuditValue(Instant now, EntityMetadata<?> metadata, String column) {
        int idx = metadata.columnIndex(column);
        if (idx < 0) return now;
        Class<?> type = metadata.selectTypes()[idx];
        if (type == LocalDateTime.class) {
            return LocalDateTime.ofInstant(now, ZoneId.systemDefault());
        }
        return now;
    }

    private static boolean isJsonCandidate(Class<?> t) {
        return List.class.isAssignableFrom(t)
                || Map.class.isAssignableFrom(t)
                || (!t.isPrimitive() && !t.isEnum()
                && !t.getName().startsWith("java.")
                && !t.getName().startsWith("javax."));
    }
}
