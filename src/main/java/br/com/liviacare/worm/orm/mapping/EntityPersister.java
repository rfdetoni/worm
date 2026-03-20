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
        final List<Object> values = new ArrayList<>(metadata.insertableColumns().size());
        final Instant now = Instant.now();
        
        // Cache frequently accessed metadata to avoid repeated method calls
        final String idCol = metadata.idColumnName();
        final Optional<String> createdAtCol = metadata.createdAtColumn();
        final Optional<String> updatedAtCol = metadata.updatedAtColumn();
        final boolean hasActive = metadata.hasActive();
        final String activeColumn = metadata.activeColumn();

        for (String column : metadata.insertableColumns()) {
            if (column.equals(idCol)) {
                try {
                    values.add(metadata.idGetter().invoke(entity));
                } catch (Throwable e) {
                    throw new IllegalStateException("Failed to read ID column '" + column + "' from entity", e);
                }
                continue;
            }
            
            // Fast-path: check audit columns by reference equality first
            if (createdAtCol.isPresent() && createdAtCol.get().equals(column)) {
                values.add(mapAuditValue(now, metadata, column));
                continue;
            }
            if (updatedAtCol.isPresent() && updatedAtCol.get().equals(column)) {
                values.add(mapAuditValue(now, metadata, column));
                continue;
            }
            
            final int idx = metadata.columnIndex(column);
            final MethodHandle getter = metadata.selectGetters()[idx];
            try {
                Object val = getter.invoke(entity);
                if (hasActive && activeColumn.equals(column)) {
                    // Primitive booleans default to false when unset; honor @Active(defaultValue)
                    // so users can control the insert-time default (true or false) explicitly.
                    if (val == null || metadata.selectTypes()[idx] == boolean.class) {
                        val = metadata.activeDefaultValue();
                    }
                }
                values.add(prepareValue(val, column, metadata, idx));
            } catch (Throwable e) {
                throw new IllegalStateException("Failed to read column '" + column + "' from entity", e);
            }
        }
        return values;
    }

    /**
     * Returns the bind-parameter array for an INSERT statement, in the
     * same column order as {@link EntityMetadata#insertableColumns()}.
     */
    public static <T> Object[] insertValuesArray(T entity, EntityMetadata<T> metadata) {
        final Object[] values = new Object[metadata.insertableColumns().size()];
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
                    values[out++] = metadata.idGetter().invoke(entity);
                } catch (Throwable e) {
                    throw new IllegalStateException("Failed to read ID column '" + column + "' from entity", e);
                }
                continue;
            }

            if (createdAtCol.isPresent() && createdAtCol.get().equals(column)) {
                values[out++] = mapAuditValue(now, metadata, column);
                continue;
            }
            if (updatedAtCol.isPresent() && updatedAtCol.get().equals(column)) {
                values[out++] = mapAuditValue(now, metadata, column);
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
                values[out++] = prepareValue(val, column, metadata, idx);
            } catch (Throwable e) {
                throw new IllegalStateException("Failed to read column '" + column + "' from entity", e);
            }
        }
        return values;
    }

    /**
     * Returns the bind-parameter list for an UPDATE statement, in the
     * same column order as {@link EntityMetadata#updatableColumns()},
     * followed by the entity's ID as the final bind value.
     */
    public static <T> List<Object> updateValues(T entity, EntityMetadata<T> metadata, Object id) {
        final List<Object> values = new ArrayList<>(metadata.updatableColumns().size() + 1);
        final Instant now = Instant.now();
        
        // Cache metadata to avoid repeated method calls
        final Optional<String> updatedAtCol = metadata.updatedAtColumn();
        final boolean hasUpdatedAt = updatedAtCol.isPresent();

        for (String column : metadata.updatableColumns()) {
            if (hasUpdatedAt && updatedAtCol.get().equals(column)) {
                values.add(mapAuditValue(now, metadata, column));
                continue;
            }
            final int idx = metadata.columnIndex(column);
            final MethodHandle getter = metadata.selectGetters()[idx];
            try {
                Object val = getter.invoke(entity);
                values.add(prepareValue(val, column, metadata, idx));
            } catch (Throwable e) {
                throw new IllegalStateException("Failed to read column '" + column + "' from entity", e);
            }
        }
        values.add(id); // WHERE id = ?
        if (metadata.hasVersion()) {
            try {
                Object ver = metadata.versionGetter().invoke(entity);
                values.add(ver); // version bind for WHERE ... AND version_col = ?
            } catch (Throwable e) {
                throw new IllegalStateException("Failed to read version value from entity", e);
            }
        }
        return values;
    }

    /**
     * Returns the bind-parameter array for an UPDATE statement, in the
     * same column order as {@link EntityMetadata#updatableColumns()},
     * followed by the entity's ID and optional version bind values.
     */
    public static <T> Object[] updateValuesArray(T entity, EntityMetadata<T> metadata, Object id) {
        final Object[] values = new Object[metadata.updatableColumns().size() + 1 + (metadata.hasVersion() ? 1 : 0)];
        final Instant now = Instant.now();

        final Optional<String> updatedAtCol = metadata.updatedAtColumn();
        final boolean hasUpdatedAt = updatedAtCol.isPresent();

        int out = 0;
        for (String column : metadata.updatableColumns()) {
            if (hasUpdatedAt && updatedAtCol.get().equals(column)) {
                values[out++] = mapAuditValue(now, metadata, column);
                continue;
            }
            final int idx = metadata.columnIndex(column);
            final MethodHandle getter = metadata.selectGetters()[idx];
            try {
                Object val = getter.invoke(entity);
                values[out++] = prepareValue(val, column, metadata, idx);
            } catch (Throwable e) {
                throw new IllegalStateException("Failed to read column '" + column + "' from entity", e);
            }
        }

        values[out++] = id;
        if (metadata.hasVersion()) {
            try {
                values[out] = metadata.versionGetter().invoke(entity);
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
