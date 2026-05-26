package com.github.rfdetoni.worm.orm.sql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.rfdetoni.worm.orm.mapping.ParamBinder;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.lang.invoke.MethodHandle;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Immutable, per-entity write plan pre-compiled once at metadata-build time.
 *
 * <p>A {@code WritePlan} encodes <em>exactly</em> how each {@code ?} placeholder in a
 * INSERT or UPDATE SQL statement is bound at call time, using pre-resolved
 * {@link MethodHandle}s and {@link Slot} descriptors.  This eliminates every
 * per-call allocation that the legacy {@code EntityPersister} path incurs:
 * <ul>
 *   <li>No {@code ArrayList} or {@code Object[]} intermediate collection.</li>
 *   <li>No {@code HashMap} lookup for column index resolution.</li>
 *   <li>No {@code String.equals()} comparisons to detect audit/id columns.</li>
 * </ul>
 *
 * <p>Each write operation reduces to:
 * <ol>
 *   <li>Optional lifecycle hook invocation ({@code iBaseEntity.created/updated}).</li>
 *   <li>One {@code Instant.now()} call shared across all audit slots.</li>
 *   <li>A tight array loop calling {@link Slot} resolution + {@link ParamBinder}.</li>
 * </ol>
 *
 * @param sql         Pre-built INSERT / UPDATE SQL with positional {@code ?} placeholders.
 * @param slots       Ordered binding descriptors — one per {@code ?} placeholder.
 * @param binder      Pre-compiled parameter binder for this plan.
 * @param hookHandle  {@code MethodHandle} for {@code iBaseEntity.created()} or
 *                    {@code updated()}, or {@code null} when the entity does not
 *                    implement {@code iBaseEntity}.
 * @param hasVersion  {@code true} when optimistic-locking is active; used by the
 *                    caller to detect stale-entity failures.
 */
public record WritePlan(
        String sql,
        Slot[] slots,
        ParamBinder binder,
        MethodHandle hookHandle,
        boolean hasVersion
) {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    public WritePlan {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("WritePlan.sql must not be blank");
        }
        if (slots == null) {
            throw new IllegalArgumentException("WritePlan.slots must not be null");
        }
        binder = (binder != null) ? binder : compileBinder(slots);
    }

    public static WritePlan compiled(String sql, Slot[] slots, MethodHandle hookHandle, boolean hasVersion) {
        return new WritePlan(sql, slots, compileBinder(slots), hookHandle, hasVersion);
    }

    // =========================================================================
    // Slot — sealed binding descriptor
    // =========================================================================

    /**
     * Sealed discriminated union describing a single {@code ?} placeholder.
     *
     * <p>Using a sealed hierarchy (rather than a plain {@code MethodHandle[]}) lets the
     * JVM emit a tight pattern-match dispatch (see {@link WritePlan#execute}) that the
     * JIT can de-virtualize and inline.
     */
    public sealed interface Slot permits Slot.Field, Slot.AuditNow, Slot.ActiveDefault {

        /**
         * Reads a value directly from the entity via a pre-resolved getter handle.
         *
         * @param getter   pre-resolved field / accessor {@code MethodHandle}
         * @param json     {@code true} → serialize to {@code PGobject("jsonb")}
         * @param isEnum   {@code true} → convert via {@link Enum#name()}
         */
        record Field(MethodHandle getter, boolean json, boolean isEnum) implements Slot {}

        /**
         * Injects the current timestamp for {@code @CreatedAt} / {@code @UpdatedAt}
         * columns.  The entity field is <em>never</em> read; the timestamp is
         * generated once per {@link WritePlan#execute} call and shared across all
         * audit slots.
         *
         * @param asLocalDateTime {@code true} when the mapped Java type is
         *                        {@link LocalDateTime} rather than {@link Instant}
         */
        record AuditNow(boolean asLocalDateTime) implements Slot {}

        /**
         * Reads from the entity but substitutes the annotation-declared default
         * when the field value is {@code null} (handles {@code @Active(defaultValue)}).
         *
         * @param getter   field getter handle
         * @param fallback value to use when the field returns {@code null}
         */
        record ActiveDefault(MethodHandle getter, Object fallback, boolean forceFallback) implements Slot {}
    }

    // =========================================================================
    // Execution — zero-allocation write path
    // =========================================================================

    /**
     * Executes the compiled write plan against the given {@link JdbcClient}.
     *
     * <p>Steps:
     * <ol>
     *   <li>Invoke the lifecycle hook ({@code iBaseEntity.created/updated}) if present.</li>
     *   <li>Capture a single {@code Instant.now()} for all audit slots.</li>
     *   <li>Bind placeholders through the pre-compiled {@link ParamBinder}.</li>
     *   <li>Execute the statement and return the affected-row count.</li>
     * </ol>
     *
     * @param client the JdbcClient to use
     * @param entity the entity being persisted; cast is unchecked but safe when
     *               WritePlan is built from the matching EntityMetadata
     * @return number of affected rows
     */
    public int execute(JdbcClient client, Object entity) {
        // Step 1 — lifecycle hook
        if (hookHandle != null) {
            try {
                hookHandle.invoke(entity);
            } catch (Throwable t) {
                sneakyThrow(t);
            }
        }

        // Step 2 — bind params through the pre-compiled binder (zero intermediate lists)
        JdbcClient.StatementSpec spec = client.sql(sql);
        try {
            spec = binder.bind(spec, entity);
        } catch (Throwable t) {
            return sneakyThrowInt(t);
        }

        // Step 3 — execute
        return spec.update();
    }

    private static ParamBinder compileBinder(Slot[] slots) {
        return (spec, entity) -> {
            final Instant now = Instant.now();
            for (Slot slot : slots) {
                final Object val = switch (slot) {
                    case Slot.Field f -> resolveField(f, entity);
                    case Slot.AuditNow a -> a.asLocalDateTime()
                            ? LocalDateTime.ofInstant(now, ZoneId.systemDefault())
                            : now;
                    case Slot.ActiveDefault d -> {
                        Object raw = d.getter().invoke(entity);
                        yield (d.forceFallback() || raw == null) ? d.fallback() : raw;
                    }
                };
                spec = spec.param(val);
            }
            return spec;
        };
    }

    // =========================================================================
    // Field value conversion
    // =========================================================================

    private static Object resolveField(Slot.Field f, Object entity) throws Throwable {
        final Object raw = f.getter().invoke(entity);
        if (raw == null) return null;
        if (f.isEnum()) return ((Enum<?>) raw).name();
        if (f.json()) return toJsonb(raw);
        return raw;
    }

    private static Object toJsonb(Object val) {
        try {
            PGobject pg = new PGobject();
            pg.setType("jsonb");
            pg.setValue(MAPPER.writeValueAsString(val));
            return pg;
        } catch (Exception e) {
            throw new RuntimeException("WritePlan: failed to serialize field to JSONB", e);
        }
    }

    // =========================================================================
    // Sneaky-throw helpers (avoid wrapping Throwable in RuntimeException)
    // =========================================================================

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    private static int sneakyThrowInt(Throwable t) {
        sneakyThrow(t);
        return 0; // unreachable
    }
}

