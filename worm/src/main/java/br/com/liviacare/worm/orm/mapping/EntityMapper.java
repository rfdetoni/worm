package br.com.liviacare.worm.orm.mapping;

import br.com.liviacare.worm.orm.registry.EntityMetadata;
import br.com.liviacare.worm.orm.registry.JoinInfo;
import br.com.liviacare.worm.orm.registry.ProjectionMetadata;

import java.lang.invoke.MethodHandle;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Responsible for mapping a JDBC ResultSet row into a Java record or class
 * instance using pre-cached MethodHandles and Converters from EntityMetadata.
 */
public final class EntityMapper {

    public record EntityRowPlan(int[] scalarIndexes, int[][] joinIndexes) {
    }

    public record ProjectionRowPlan(int[] indexes) {
    }

    /**
     * Per-thread reusable arrays for hot mapping paths.
     *
     * <p>Why: row mapping is allocation-sensitive under large scans. Reusing exact-sized
     * argument arrays removes transient {@code Object[]} churn from the young generation.
     */
    private static final ThreadLocal<MapperScratch> SCRATCH = ThreadLocal.withInitial(MapperScratch::new);

    private static final class MapperScratch {
        private Object[] ctorArgs;
        private Object[] projectionArgs;
        private Object[][] joinArgs;
        /** Reusable raw extraction buffer — NOT safe to hold across rows; copy if needed. */
        private Object[] rawBuffer;

        Object[] ctorArgs(int size) {
            if (ctorArgs == null || ctorArgs.length != size) {
                ctorArgs = new Object[size];
            } else {
                Arrays.fill(ctorArgs, null);
            }
            return ctorArgs;
        }

        Object[] projectionArgs(int size) {
            if (projectionArgs == null || projectionArgs.length != size) {
                projectionArgs = new Object[size];
            } else {
                Arrays.fill(projectionArgs, null);
            }
            return projectionArgs;
        }

        Object[] joinArgs(int paramIndex, int size) {
            if (joinArgs == null || joinArgs.length <= paramIndex) {
                joinArgs = (joinArgs == null)
                        ? new Object[Math.max(paramIndex + 1, 8)][]
                        : Arrays.copyOf(joinArgs, Math.max(paramIndex + 1, joinArgs.length * 2));
            }
            Object[] values = joinArgs[paramIndex];
            if (values == null || values.length != size) {
                values = new Object[size];
                joinArgs[paramIndex] = values;
            } else {
                Arrays.fill(values, null);
            }
            return values;
        }

        /**
         * Returns a reusable thread-local raw-extraction buffer of at least {@code size}.
         * <strong>Callers must copy the array before handing it off to another thread</strong>
         * (the parallel path in OrmManager always copies via {@link EntityMapper#extractRaw}).
         */
        Object[] rawBuffer(int size) {
            if (rawBuffer == null || rawBuffer.length < size) {
                rawBuffer = new Object[size];
            } else {
                Arrays.fill(rawBuffer, 0, size, null);
            }
            return rawBuffer;
        }
    }

    private EntityMapper() {
    }

    public static EntityRowPlan prepareEntityRowPlan(ResultSet rs, EntityMetadata<?> metadata) throws SQLException {
        final String[] labels = metadata.paramColumnLabels();
        final JoinInfo[] joins = metadata.joinInfos();
        final int params = metadata.paramCount();
        final int[] scalarIndexes = new int[params];
        final int[][] joinIndexes = new int[params][];
        final Map<String, Integer> byLabel = readLabelToIndex(rs);

        for (int i = 0; i < params; i++) {
            String label = labels[i];
            if (label != null) {
                // PERF: use precomputed positional access in hot row mapping.
                scalarIndexes[i] = byLabel.getOrDefault(label.toLowerCase(Locale.ROOT), -1);
                continue;
            }
            JoinInfo ji = joins != null ? joins[i] : null;
            if (ji == null) {
                continue;
            }
            List<String> joinLabels = ji.getResultLabels();
            int[] indexes = new int[joinLabels.size()];
            for (int k = 0; k < joinLabels.size(); k++) {
                // PERF: resolve join result labels once per ResultSet.
                indexes[k] = byLabel.getOrDefault(joinLabels.get(k).toLowerCase(Locale.ROOT), -1);
            }
            joinIndexes[i] = indexes;
        }
        return new EntityRowPlan(scalarIndexes, joinIndexes);
    }

    public static ProjectionRowPlan prepareProjectionRowPlan(ResultSet rs, ProjectionMetadata projMeta) throws SQLException {
        final String[] labels = projMeta.selectedLabels();
        final int[] indexes = new int[labels.length];
        final Map<String, Integer> byLabel = readLabelToIndex(rs);
        for (int i = 0; i < labels.length; i++) {
            // PERF: avoid column-name lookup for projection mapping on every row.
            indexes[i] = byLabel.getOrDefault(labels[i].toLowerCase(Locale.ROOT), -1);
        }
        return new ProjectionRowPlan(indexes);
    }

    private static Map<String, Integer> readLabelToIndex(ResultSet rs) throws SQLException {
        var md = rs.getMetaData();
        int count = md.getColumnCount();
        Map<String, Integer> byLabel = new HashMap<>(count * 2);
        for (int i = 1; i <= count; i++) {
            // PERF: lowercase labels once so matching is case-insensitive with zero per-row normalization.
            byLabel.putIfAbsent(md.getColumnLabel(i).toLowerCase(Locale.ROOT), i);
        }
        return byLabel;
    }

    @SuppressWarnings("unchecked")
    public static <T> T mapRow(ResultSet rs, EntityMetadata<T> metadata) throws SQLException {
        return mapRow(rs, metadata, prepareEntityRowPlan(rs, metadata));
    }

    @SuppressWarnings("unchecked")
    public static <T> T mapRow(ResultSet rs, EntityMetadata<T> metadata, EntityRowPlan rowPlan) throws SQLException {
        try {
            final int params = metadata.paramCount();
            final boolean isRecord = metadata.isRecord();
            final MapperScratch scratch = SCRATCH.get();
            final Object[] ctorArgs = isRecord ? scratch.ctorArgs(params) : null;

            final String[] paramLabels = metadata.paramColumnLabels();
            final ColumnConverter[] paramConverters = metadata.paramConverters();
            final MethodHandle[] paramSetters = metadata.paramSetters();
            final JoinInfo[] joins = metadata.joinInfos();
            final int[] scalarIndexes = rowPlan.scalarIndexes();
            final int[][] joinIndexes = rowPlan.joinIndexes();

            // If we are mapping a class (not a record), we first instantiate it using no-arg constructor
            Object instance = null;
            if (!isRecord) {
                instance = metadata.constructor().invoke();
            }

            // Fast-path: no joins means we can skip join-specific logic entirely
            final boolean hasJoins = joins != null && joins.length > 0;

            for (int i = 0; i < params; i++) {
                if (paramLabels[i] != null) {
                    // Simple column mapped param: get raw value and apply pre-calculated converter
                    int idx = scalarIndexes[i];
                    Object raw = idx > 0 ? rs.getObject(idx) : null;
                    Object val = paramConverters[i].convert(raw);

                    if (isRecord) {
                        ctorArgs[i] = val;
                    } else {
                        // For class, invoke setter immediately
                        final MethodHandle setter = paramSetters[i];
                        if (setter != null) {
                            setter.invoke(instance, val);
                        }
                    }
                } else if (hasJoins) {
                    // Join param: reconstruct object from its result labels
                    final JoinInfo ji = joins[i];
                    if (ji == null) {
                        if (isRecord) ctorArgs[i] = null;
                        continue;
                    }

                    final List<String> labels = ji.getResultLabels();
                    final ColumnConverter[] joinConverters = ji.getJoinConverters();
                    final Object[] joinValues = scratch.joinArgs(i, labels.size());
                    final int[] indexes = joinIndexes[i];

                    boolean anyNonNull = false;
                    for (int k = 0; k < labels.size(); k++) {
                        int idx = indexes != null && k < indexes.length ? indexes[k] : -1;
                        Object val = idx > 0 ? rs.getObject(idx) : null;
                        // Apply per-column converters for proper type conversion (UUID, LocalDateTime, Enum, etc.)
                        if (joinConverters != null && k < joinConverters.length && joinConverters[k] != null) {
                            val = joinConverters[k].convert(val);
                        }
                        if (val != null) anyNonNull = true;
                        joinValues[k] = val;
                    }

                    Object joinInstance = null;
                    if (anyNonNull) {
                        if (ji.isRecord()) {
                            MethodHandle spreader = ji.getJoinConstructorSpreader();
                            joinInstance = (spreader != null)
                                    ? spreader.invoke(joinValues)
                                    : ji.getJoinConstructor().invokeWithArguments(joinValues);
                        } else {
                            joinInstance = ji.getJoinConstructor().invoke();
                            final MethodHandle[] setters = ji.getJoinSetters();
                            for (int k = 0; k < setters.length && k < joinValues.length; k++) {
                                if (setters[k] != null) {
                                    setters[k].invoke(joinInstance, joinValues[k]);
                                }
                            }
                        }
                    }

                    // If the field is List/Collection, wrap the single item in a mutable list
                    // (OrmManager will later merge lists from multiple rows with the same ID)
                    final Object fieldValue;
                    if (ji.isList()) {
                        fieldValue = (joinInstance == null) ? Collections.emptyList() : List.of(joinInstance);
                    } else {
                        fieldValue = joinInstance;
                    }

                    if (isRecord) {
                        ctorArgs[i] = fieldValue;
                    } else {
                        final MethodHandle setter = paramSetters[i];
                        if (setter != null) {
                            setter.invoke(instance, fieldValue);
                        }
                    }
                } else if (isRecord) {
                    ctorArgs[i] = null;
                }
            }

            if (isRecord) {
                MethodHandle spreader = metadata.constructorSpreader();
                return (T) (spreader != null
                        ? spreader.invoke(ctorArgs)
                        : metadata.constructor().invokeWithArguments(ctorArgs));
            } else {
                return (T) instance;
            }

        } catch (Throwable e) {
            // Fix: Safe check for message to avoid NPE in catch block
            String msg = e.getMessage();
            if (msg != null && msg.startsWith("Failed to deserialize JSON")) {
                 throw new SQLException("Failed to map row due to JSON deserialization error.", e);
            }
            if (e instanceof SQLException se) throw se;
            throw new SQLException("Failed to map row to " + metadata.entityClass().getName(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Two-phase parallel mapping API
    // Phase 1 (sequential, JDBC-bound): extract raw column values from ResultSet.
    // Phase 2 (parallel-safe, CPU-only): convert raw values and construct entity.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Phase 1 of parallel mapping: reads all column values for the current ResultSet row
     * into a plain {@code Object[]} without performing any type conversion or entity construction.
     *
     * <p>Must be called from the JDBC RowMapper callback while the cursor is positioned on the row.
     * The returned array can safely be handed off to a worker thread for Phase 2.
     *
     * <p>Encoding:
     * <ul>
     *   <li>Simple column at index {@code i}: {@code raw[i] = rs.getObject(paramLabels[i])}</li>
     *   <li>Join slot at index {@code i}: {@code raw[i] = Object[]} of per-join-column raw values</li>
     *   <li>Null / missing join: {@code raw[i] = null}</li>
     * </ul>
     */
    public static Object[] extractRaw(ResultSet rs, EntityMetadata<?> metadata) throws SQLException {
        return extractRaw(rs, metadata, prepareEntityRowPlan(rs, metadata));
    }

    public static Object[] extractRaw(ResultSet rs, EntityMetadata<?> metadata, EntityRowPlan rowPlan) throws SQLException {
        final int params = metadata.paramCount();
        final String[] paramLabels = metadata.paramColumnLabels();
        final JoinInfo[] joins = metadata.joinInfos();
        final boolean hasJoins = joins != null && joins.length > 0;
        final Object[] raw = new Object[params];
        final int[] scalarIndexes = rowPlan.scalarIndexes();
        final int[][] joinIndexes = rowPlan.joinIndexes();
        for (int i = 0; i < params; i++) {
            if (paramLabels[i] != null) {
                int idx = scalarIndexes[i];
                raw[i] = idx > 0 ? rs.getObject(idx) : null;
            } else if (hasJoins) {
                final JoinInfo ji = joins[i];
                if (ji == null) {
                    raw[i] = null;
                    continue;
                }
                final List<String> labels = ji.getResultLabels();
                final Object[] joinRaw = new Object[labels.size()];
                final int[] indexes = joinIndexes[i];
                for (int k = 0; k < labels.size(); k++) {
                    int idx = indexes != null && k < indexes.length ? indexes[k] : -1;
                    joinRaw[k] = idx > 0 ? rs.getObject(idx) : null;
                }
                raw[i] = joinRaw;
            } else {
                raw[i] = null;
            }
        }
        return raw;
    }

    /**
     * Phase 2 of parallel mapping: constructs a typed entity from a raw value array
     * previously produced by {@link #extractRaw}.
     *
     * <p>This method is pure-CPU and has no JDBC dependency, making it safe to invoke
     * from any thread (e.g. via {@code parallelStream()} or a virtual-thread executor).
     */
    @SuppressWarnings("unchecked")
    public static <T> T mapFromRaw(Object[] raw, EntityMetadata<T> metadata) throws Throwable {
        final int params = metadata.paramCount();
        final boolean isRecord = metadata.isRecord();
        final MapperScratch scratch = SCRATCH.get();
        final Object[] ctorArgs = isRecord ? scratch.ctorArgs(params) : null;
        final String[] paramLabels = metadata.paramColumnLabels();
        final ColumnConverter[] paramConverters = metadata.paramConverters();
        final MethodHandle[] paramSetters = metadata.paramSetters();
        final JoinInfo[] joins = metadata.joinInfos();
        final boolean hasJoins = joins != null && joins.length > 0;

        Object instance = null;
        if (!isRecord) {
            instance = metadata.constructor().invoke();
        }

        for (int i = 0; i < params; i++) {
            if (paramLabels[i] != null) {
                Object val = paramConverters[i].convert(raw[i]);
                if (isRecord) {
                    ctorArgs[i] = val;
                } else {
                    final MethodHandle setter = paramSetters[i];
                    if (setter != null) setter.invoke(instance, val);
                }
            } else if (hasJoins) {
                final JoinInfo ji = joins[i];
                if (ji == null) {
                    if (isRecord) ctorArgs[i] = null;
                    continue;
                }
                final Object[] joinRaw = (Object[]) raw[i];
                final ColumnConverter[] joinConverters = ji.getJoinConverters();
                final int joinLen = joinRaw != null ? joinRaw.length : 0;
                final Object[] joinValues = scratch.joinArgs(i, joinLen);
                boolean anyNonNull = false;
                for (int k = 0; k < joinLen; k++) {
                    Object val = joinRaw[k];
                    if (joinConverters != null && k < joinConverters.length && joinConverters[k] != null) {
                        val = joinConverters[k].convert(val);
                    }
                    if (val != null) anyNonNull = true;
                    joinValues[k] = val;
                }
                Object joinInstance = null;
                if (anyNonNull) {
                    if (ji.isRecord()) {
                        MethodHandle spreader = ji.getJoinConstructorSpreader();
                        joinInstance = (spreader != null)
                                ? spreader.invoke(joinValues)
                                : ji.getJoinConstructor().invokeWithArguments(joinValues);
                    } else {
                        joinInstance = ji.getJoinConstructor().invoke();
                        final MethodHandle[] setters = ji.getJoinSetters();
                        for (int k = 0; k < setters.length && k < joinValues.length; k++) {
                            if (setters[k] != null) setters[k].invoke(joinInstance, joinValues[k]);
                        }
                    }
                }
                final Object fieldValue;
                if (ji.isList()) {
                    fieldValue = (joinInstance == null) ? Collections.emptyList() : List.of(joinInstance);
                } else {
                    fieldValue = joinInstance;
                }
                if (isRecord) {
                    ctorArgs[i] = fieldValue;
                } else {
                    final MethodHandle setter = paramSetters[i];
                    if (setter != null) setter.invoke(instance, fieldValue);
                }
            } else if (isRecord) {
                ctorArgs[i] = null;
            }
        }

        if (isRecord) {
            MethodHandle spreader = metadata.constructorSpreader();
            return (T) (spreader != null
                    ? spreader.invoke(ctorArgs)
                    : metadata.constructor().invokeWithArguments(ctorArgs));
        } else {
            return (T) instance;
        }
    }

    /**
     * Phase 1 for projection mapping: extracts raw column values from the current ResultSet row.
     * Parallel-safe counterpart of {@link #mapToProjection}.
     */
    public static Object[] extractRawProjection(ResultSet rs, ProjectionMetadata projMeta) throws SQLException {
        return extractRawProjection(rs, projMeta, prepareProjectionRowPlan(rs, projMeta));
    }

    public static Object[] extractRawProjection(ResultSet rs, ProjectionMetadata projMeta, ProjectionRowPlan rowPlan) throws SQLException {
        final String[] labels = projMeta.selectedLabels();
        final Object[] raw = new Object[labels.length];
        final int[] indexes = rowPlan.indexes();
        for (int i = 0; i < labels.length; i++) {
            int idx = indexes[i];
            raw[i] = idx > 0 ? rs.getObject(idx) : null;
        }
        return raw;
    }

    /**
     * Phase 2 for projection mapping: constructs a typed projection from raw values
     * previously produced by {@link #extractRawProjection}. Pure-CPU, no JDBC dependency.
     */
    @SuppressWarnings("unchecked")
    public static <P> P mapProjectionFromRaw(Object[] raw, ProjectionMetadata projMeta) throws Throwable {
        final ColumnConverter[] convs = projMeta.converters();
        final Object[] args = SCRATCH.get().projectionArgs(raw.length);
        for (int i = 0; i < raw.length; i++) {
            Object conv = convs[i].convert(raw[i]);
            Class<?> expected = projMeta.componentTypes()[i];
            if (conv != null && (expected == List.class || expected == Collection.class)
                    && !(conv instanceof Collection)) {
                args[i] = List.of(conv);
            } else {
                args[i] = conv;
            }
        }
        return (P) projMeta.constructor().invokeWithArguments(args);
    }

    public static <P> P mapToProjection(ResultSet rs, ProjectionMetadata projMeta) throws Throwable {
        return mapToProjection(rs, projMeta, prepareProjectionRowPlan(rs, projMeta));
    }

    public static <P> P mapToProjection(ResultSet rs, ProjectionMetadata projMeta, ProjectionRowPlan rowPlan) throws Throwable {
        final String[] labels = projMeta.selectedLabels();
        final Object[] args = SCRATCH.get().projectionArgs(labels.length);
        final br.com.liviacare.worm.orm.mapping.ColumnConverter[] convs = projMeta.converters();
        final int[] indexes = rowPlan.indexes();
        for (int i = 0; i < labels.length; i++) {
            int idx = indexes[i];
            Object raw = idx > 0 ? rs.getObject(idx) : null;
            Object conv = convs[i].convert(raw);
            // If projection constructor expects a List/Collection but the converter returned a single element
            // (common when joins are projected and produce a single joined object), wrap it into a List.
            Class<?> expected = projMeta.componentTypes()[i];
            if (conv != null && (expected == List.class || expected == Collection.class)
                    && !(conv instanceof Collection)) {
                args[i] = List.of(conv);
            } else {
                args[i] = conv;
            }
        }
        return (P) projMeta.constructor().invokeWithArguments(args);
    }

    /**
     * Merges rows that share the same entity ID when the entity has one-to-many (List) joins.
     * Each raw row produces an entity with a single-element list per collection join field.
     * This method groups rows by ID and accumulates those lists into a single entity per ID.
     *
     * <p>For records: re-invokes the canonical constructor with merged args.
     * For classes: invokes setters with the merged lists.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> List<T> mergeCollectionJoins(List<T> rawRows, EntityMetadata<T> metadata) {
        if (rawRows == null || rawRows.isEmpty()) return rawRows;

        final JoinInfo[] joins = metadata.joinInfos();
        final MethodHandle idGetter = metadata.idGetter();
        final int paramCount = metadata.paramCount();
        final String[] paramLabels = metadata.paramColumnLabels();
        final MethodHandle[] paramSetters = metadata.paramSetters();

        int[] listJoinIndices = new int[paramCount];
        int listJoinCount = 0;
        for (int i = 0; i < paramCount; i++) {
            if (paramLabels[i] == null && joins[i] != null && joins[i].isList()) {
                listJoinIndices[listJoinCount++] = i;
            }
        }
        if (listJoinCount == 0) return rawRows;

        // PERF: LinkedHashMap preserves insertion order, eliminating a sort pass over the output list.
        final Map<Object, T> firstById = new LinkedHashMap<>();
        final Map<Object, ArrayList<Object>[]> accumulatorsById = new LinkedHashMap<>();

        for (T row : rawRows) {
            Object rowId = entityId(row, idGetter);
            if (!firstById.containsKey(rowId)) {
                firstById.put(rowId, row);
                accumulatorsById.put(rowId, initListAccumulators(row, metadata, listJoinIndices, listJoinCount));
            } else {
                mergeIncomingLists(row, metadata, listJoinIndices, listJoinCount, accumulatorsById.get(rowId));
            }
        }

        if (firstById.size() == rawRows.size()) {
            return rawRows;
        }

        final List<T> merged = new ArrayList<>(firstById.size());
        for (var entry : firstById.entrySet()) {
            ArrayList<Object>[] lists = accumulatorsById.get(entry.getKey());
            merged.add(finalizeMergedRow(entry.getValue(), metadata, listJoinIndices, listJoinCount, lists, paramSetters));
        }
        return merged;
    }

    private static <T> Object entityId(T row, MethodHandle idGetter) {
        try {
            return idGetter.invoke(row);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to read entity ID during collection-join merge", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ArrayList<Object>[] initListAccumulators(
            T row,
            EntityMetadata<T> metadata,
            int[] listJoinIndices,
            int listJoinCount) {
        ArrayList<Object>[] lists = new ArrayList[listJoinCount];
        for (int k = 0; k < listJoinCount; k++) {
            List<Object> incoming = getListFieldView(row, metadata, listJoinIndices[k]);
            ArrayList<Object> acc = new ArrayList<>(incoming.size());
            acc.addAll(incoming);
            lists[k] = acc;
        }
        return lists;
    }

    private static <T> void mergeIncomingLists(
            T row,
            EntityMetadata<T> metadata,
            int[] listJoinIndices,
            int listJoinCount,
            ArrayList<Object>[] accumulators) {
        for (int k = 0; k < listJoinCount; k++) {
            List<Object> incoming = getListFieldView(row, metadata, listJoinIndices[k]);
            if (!incoming.isEmpty()) {
                accumulators[k].addAll(incoming);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> T finalizeMergedRow(
            T current,
            EntityMetadata<T> metadata,
            int[] listJoinIndices,
            int listJoinCount,
            ArrayList<Object>[] accumulators,
            MethodHandle[] paramSetters) {
        if (metadata.isRecord()) {
            try {
                Object[] args = extractRecordArgs(current, metadata);
                for (int k = 0; k < listJoinCount; k++) {
                    args[listJoinIndices[k]] = List.copyOf(accumulators[k]);
                }
                MethodHandle spreader = metadata.constructorSpreader();
                return (T) (spreader != null
                        ? spreader.invoke(args)
                        : metadata.constructor().invokeWithArguments(args));
            } catch (Throwable e) {
                throw new RuntimeException("Failed to reconstruct entity during collection-join merge", e);
            }
        }

        JoinInfo[] joins = metadata.joinInfos();
        for (int k = 0; k < listJoinCount; k++) {
            int pi = listJoinIndices[k];
            JoinInfo ji = joins[pi];
            List<?> finalList = accumulators[k];
            try {
                java.lang.reflect.Field field = ji.getJoinField();
                if (field != null) {
                    field.set(current, finalList);
                } else {
                    MethodHandle setter = paramSetters[pi];
                    if (setter != null) setter.invoke(current, finalList);
                }
            } catch (Throwable e) {
                throw new RuntimeException("Failed to set merged join list on entity field at index " + pi, e);
            }
        }
        return current;
    }

    /** Reads the list stored in a collection-join param slot from a mapped entity instance. */
    @SuppressWarnings("unchecked")
    private static <T> List<Object> getListFieldView(T entity, EntityMetadata<T> metadata, int paramIndex) {
        if (metadata.isRecord()) {
            try {
                java.lang.reflect.RecordComponent[] components = entity.getClass().getRecordComponents();
                if (paramIndex < components.length) {
                    Object val = components[paramIndex].getAccessor().invoke(entity);
                    if (val instanceof List<?> l) return (List<Object>) l;
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to read list join from record component " + paramIndex, e);
            }
            return Collections.emptyList();
        } else {
            // For POJO classes: use direct Field reflection for reliable generic List access
            JoinInfo ji = metadata.joinInfos()[paramIndex];
            if (ji != null) {
                java.lang.reflect.Field field = ji.getJoinField();
                if (field != null) {
                    try {
                        Object val = field.get(entity);
                        if (val instanceof List<?> l) return (List<Object>) l;
                        return Collections.emptyList();
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException("Failed to read list join field at index " + paramIndex, e);
                    }
                }
                // fallback: MethodHandle getter
                if (ji.getFieldGetter() != null) {
                    try {
                        Object val = ji.getFieldGetter().invoke(entity);
                        if (val instanceof List<?> l) return (List<Object>) l;
                    } catch (Throwable e) {
                        throw new RuntimeException("Failed to read list join via getter at index " + paramIndex, e);
                    }
                }
            }
            return Collections.emptyList();
        }
    }

    /** Extracts all constructor arguments from a record instance using its component accessors. */
    private static <T> Object[] extractRecordArgs(T entity, EntityMetadata<T> metadata) {
        java.lang.reflect.RecordComponent[] components = entity.getClass().getRecordComponents();
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            try {
                args[i] = components[i].getAccessor().invoke(entity);
            } catch (Exception e) {
                throw new RuntimeException("Failed to read record component " + i + " during merge", e);
            }
        }
        return args;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Single-pass drain+merge for Cartesian JOIN result sets (Gap 2 fix)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Drains a full {@link java.sql.ResultSet} directly into a deduplicated entity list
     * for entities with one-to-many collection joins.
     *
     * <p>Strategy (single pass, serial, JDBC-bound):
     * <ol>
     *   <li>For the <b>first row</b> of each unique PK: call {@link #mapRow} to create the
     *       left-side entity, then initialise per-PK {@code ArrayList} accumulators from
     *       the entity's single-element list join slots.</li>
     *   <li>For <b>subsequent rows</b> with the same PK: read <em>only</em> the list-join
     *       columns and append the child objects to the existing accumulators —
     *       <strong>skipping full entity construction entirely</strong>.</li>
     *   <li>Finalise each entity by setting its accumulated list(s) via
     *       {@link #finalizeMergedRow}, then return.</li>
     * </ol>
     *
     * <p>PERF: LinkedHashMap preserves insertion order, eliminating a sort pass over the output list.
     * <p>PERF: Only M entity instances are live simultaneously (M = unique PKs), never N
     *          (N = total Cartesian rows). This reduces heap delta proportional to (N-M) / N.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> List<T> drainAndMergeCollectionJoins(java.sql.ResultSet rs,
                                                            EntityMetadata<T> metadata) throws java.sql.SQLException {
        final MethodHandle idGetter = metadata.idGetter();
        final int paramCount = metadata.paramCount();
        final String[] paramLabels = metadata.paramColumnLabels();
        final JoinInfo[] joins = metadata.joinInfos();
        final MethodHandle[] paramSetters = metadata.paramSetters();

        // Identify list-join param indices once
        int[] listJoinIndices = new int[paramCount];
        int listJoinCount = 0;
        for (int i = 0; i < paramCount; i++) {
            if (paramLabels[i] == null && joins != null && joins[i] != null && joins[i].isList()) {
                listJoinIndices[listJoinCount++] = i;
            }
        }

        if (listJoinCount == 0) {
            // No list joins — simple sequential drain, no merge needed
            List<T> results = new ArrayList<>();
            EntityRowPlan[] planRef = new EntityRowPlan[1];
            while (rs.next()) {
                if (planRef[0] == null) planRef[0] = prepareEntityRowPlan(rs, metadata);
                results.add(mapRow(rs, metadata, planRef[0]));
            }
            return results;
        }

        // PERF: LinkedHashMap preserves insertion order, eliminating a sort pass over the output list.
        final LinkedHashMap<Object, T> entityMap = new LinkedHashMap<>();
        // PERF: separate accumulator map avoids holding N entity instances when rows >> unique PKs.
        final LinkedHashMap<Object, ArrayList<Object>[]> accumMap = new LinkedHashMap<>();

        EntityRowPlan plan = null;
        // PERF: RS column index for PK — precomputed once to enable O(1) PK peeking per row
        //       without constructing a full entity, allowing fast duplicate-PK detection.
        int pkRsIndex = -1;

        while (rs.next()) {
            if (plan == null) {
                plan = prepareEntityRowPlan(rs, metadata);
                // Resolve PK column's ResultSet index from the label matching idColumnName
                final String idColLower = metadata.idColumnName().toLowerCase(Locale.ROOT);
                for (int i = 0; i < paramCount; i++) {
                    if (paramLabels[i] == null) continue;
                    final String lbl = paramLabels[i].toLowerCase(Locale.ROOT);
                    // Handle both plain "id" and "alias.id" label formats
                    if (lbl.equals(idColLower) || lbl.endsWith("." + idColLower)) {
                        final int rsIdx = plan.scalarIndexes()[i];
                        if (rsIdx > 0) {
                            pkRsIndex = rsIdx;
                            break;
                        }
                    }
                }
            }

            // PERF: peek PK directly from RS before full entity construction to skip
            //       the entity builder entirely for duplicate-PK rows.
            final Object pk = (pkRsIndex > 0) ? rs.getObject(pkRsIndex) : null;

            if (pk == null || !entityMap.containsKey(pk)) {
                // First occurrence: build full entity then init per-PK accumulators
                final T entity = mapRow(rs, metadata, plan);
                final Object resolvedPk;
                if (pk != null) {
                    resolvedPk = pk;
                } else {
                    try {
                        resolvedPk = idGetter.invoke(entity);
                    } catch (Throwable e) {
                        throw new java.sql.SQLException("Failed to read entity PK during join merge", e);
                    }
                }
                entityMap.put(resolvedPk, entity);
                accumMap.put(resolvedPk,
                        initListAccumulators(entity, metadata, listJoinIndices, listJoinCount));
            } else {
                // PERF: duplicate PK row — read only list-join child columns from the RS,
                //       skipping full entity construction (scalar columns, converters, ctor).
                appendListChildItems(rs, metadata, plan, listJoinIndices, listJoinCount, accumMap.get(pk));
            }
        }

        if (entityMap.isEmpty()) return List.of();

        // Finalise: set accumulated lists into entities
        // PERF: return new ArrayList<>(accumulator.values()) — no additional copy.
        final List<T> result = new ArrayList<>(entityMap.size());
        for (Map.Entry<Object, T> entry : entityMap.entrySet()) {
            result.add(finalizeMergedRow(
                    entry.getValue(), metadata, listJoinIndices, listJoinCount,
                    accumMap.get(entry.getKey()), paramSetters));
        }
        return result;
    }

    /**
     * For a duplicate-PK row in a Cartesian JOIN result, reads only the list-join child
     * columns from the current {@link java.sql.ResultSet} row and appends child objects to
     * the per-PK accumulators.
     *
     * <p>PERF: skips all scalar column reads, type converters for non-join params, and
     *          entity allocation — only join constructors are invoked.
     */
    private static <T> void appendListChildItems(java.sql.ResultSet rs,
                                                  EntityMetadata<T> metadata,
                                                  EntityRowPlan plan,
                                                  int[] listJoinIndices,
                                                  int listJoinCount,
                                                  ArrayList<Object>[] accumulators) throws java.sql.SQLException {
        final JoinInfo[] joins = metadata.joinInfos();
        // PERF: reuse thread-local scratch buffer to avoid per-row join-values allocation.
        final MapperScratch scratch = SCRATCH.get();

        for (int k = 0; k < listJoinCount; k++) {
            final int pi = listJoinIndices[k];
            final JoinInfo ji = joins[pi];
            if (ji == null) continue;

            final List<String> labels = ji.getResultLabels();
            final ColumnConverter[] joinConverters = ji.getJoinConverters();
            final int[] indexes = plan.joinIndexes()[pi];
            final Object[] joinValues = scratch.joinArgs(pi, labels.size());

            boolean anyNonNull = false;
            for (int j = 0; j < labels.size(); j++) {
                final int idx = (indexes != null && j < indexes.length) ? indexes[j] : -1;
                Object val = idx > 0 ? rs.getObject(idx) : null;
                if (joinConverters != null && j < joinConverters.length && joinConverters[j] != null) {
                    val = joinConverters[j].convert(val);
                }
                if (val != null) anyNonNull = true;
                joinValues[j] = val;
            }

            if (anyNonNull) {
                try {
                    final Object joinInstance;
                    if (ji.isRecord()) {
                        final MethodHandle spreader = ji.getJoinConstructorSpreader();
                        joinInstance = (spreader != null)
                                ? spreader.invoke(joinValues)
                                : ji.getJoinConstructor().invokeWithArguments(joinValues);
                    } else {
                        joinInstance = ji.getJoinConstructor().invoke();
                        final MethodHandle[] setters = ji.getJoinSetters();
                        for (int j = 0; j < setters.length && j < joinValues.length; j++) {
                            if (setters[j] != null) setters[j].invoke(joinInstance, joinValues[j]);
                        }
                    }
                    accumulators[k].add(joinInstance);
                } catch (Throwable e) {
                    throw new java.sql.SQLException("Failed to construct join child object during drain+merge", e);
                }
            }
        }
    }
}
