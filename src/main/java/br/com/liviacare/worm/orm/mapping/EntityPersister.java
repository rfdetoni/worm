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

        for (String column : metadata.insertableColumns()) {
            if (column.equals(metadata.idColumnName())) {
                try {
                    values.add(metadata.idGetter().invoke(entity));
                } catch (Throwable e) {
                    throw new IllegalStateException("Failed to read ID column '" + column + "' from entity", e);
                }
                continue;
            }
            if (metadata.createdAtColumn().isPresent() && metadata.createdAtColumn().get().equals(column)) {
                values.add(mapAuditValue(now, metadata, column));
                continue;
            }
            if (metadata.updatedAtColumn().isPresent() && metadata.updatedAtColumn().get().equals(column)) {
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

        for (String column : metadata.updatableColumns()) {
            if (metadata.updatedAtColumn().isPresent() && metadata.updatedAtColumn().get().equals(column)) {
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
