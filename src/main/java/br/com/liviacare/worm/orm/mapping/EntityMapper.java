package br.com.liviacare.worm.orm.mapping;

import br.com.liviacare.worm.orm.registry.EntityMetadata;
import br.com.liviacare.worm.orm.registry.JoinInfo;
import br.com.liviacare.worm.orm.registry.ProjectionMetadata;

import java.lang.invoke.MethodHandle;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Responsible for mapping a JDBC ResultSet row into a Java record or class
 * instance using pre-cached MethodHandles and Converters from EntityMetadata.
 */
public final class EntityMapper {

    private EntityMapper() {
    }

    @SuppressWarnings("unchecked")
    public static <T> T mapRow(ResultSet rs, EntityMetadata<T> metadata) throws SQLException {
        try {
            final int params = metadata.paramCount();
            final Object[] ctorArgs = new Object[params];

            final String[] paramLabels = metadata.paramColumnLabels();
            final ColumnConverter[] paramConverters = metadata.paramConverters();
            final MethodHandle[] paramSetters = metadata.paramSetters();
            final JoinInfo[] joins = metadata.joinInfos();

            // If we are mapping a class (not a record), we first instantiate it using no-arg constructor
            Object instance = null;
            if (!metadata.isRecord()) {
                instance = metadata.constructor().invoke();
            }

            // Fast-path: no joins means we can skip join-specific logic entirely
            final boolean hasJoins = joins != null && joins.length > 0;

            for (int i = 0; i < params; i++) {
                if (paramLabels[i] != null) {
                    // Simple column mapped param: get raw value and apply pre-calculated converter
                    Object raw = rs.getObject(paramLabels[i]);
                    Object val = paramConverters[i].convert(raw);

                    if (metadata.isRecord()) {
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
                        if (metadata.isRecord()) ctorArgs[i] = null;
                        continue;
                    }

                    final List<String> labels = ji.getResultLabels();
                    final ColumnConverter[] joinConverters = ji.getJoinConverters();
                    final Object[] joinValues = new Object[labels.size()];

                    boolean anyNonNull = false;
                    for (int k = 0; k < labels.size(); k++) {
                        final String lbl = labels.get(k);
                        Object val = rs.getObject(lbl);
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
                            joinInstance = ji.getJoinConstructor().invokeWithArguments(joinValues);
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
                        List<Object> singleItemList = new ArrayList<>();
                        if (joinInstance != null) singleItemList.add(joinInstance);
                        fieldValue = singleItemList;
                    } else {
                        fieldValue = joinInstance;
                    }

                    if (metadata.isRecord()) {
                        ctorArgs[i] = fieldValue;
                    } else {
                        // For POJO join fields: use direct Field reflection for reliable generic List assignment
                        java.lang.reflect.Field rawField = ji.getJoinField();
                        if (rawField != null) {
                            rawField.set(instance, fieldValue);
                        } else {
                            final MethodHandle setter = paramSetters[i];
                            if (setter != null) {
                                setter.invoke(instance, fieldValue);
                            }
                        }
                    }
                } else if (metadata.isRecord()) {
                    ctorArgs[i] = null;
                }
            }

            if (metadata.isRecord()) {
                return (T) metadata.constructor().invokeWithArguments(ctorArgs);
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

    public static <P> P mapToProjection(ResultSet rs, ProjectionMetadata projMeta) throws Throwable {
        final String[] labels = projMeta.selectedLabels();
        final Object[] args = new Object[labels.length];
        final br.com.liviacare.worm.orm.mapping.ColumnConverter[] convs = projMeta.converters();
        for (int i = 0; i < labels.length; i++) {
            Object raw = rs.getObject(labels[i]);
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

        final JoinInfo[] joins        = metadata.joinInfos();
        final MethodHandle idGetter   = metadata.idGetter();
        final int paramCount          = metadata.paramCount();
        final String[] paramLabels    = metadata.paramColumnLabels();
        final MethodHandle[] paramSetters = metadata.paramSetters();

        // Identify which param indices correspond to collection joins
        int[] listJoinIndices = new int[paramCount];
        int listJoinCount = 0;
        for (int i = 0; i < paramCount; i++) {
            if (paramLabels[i] == null && joins[i] != null && joins[i].isList()) {
                listJoinIndices[listJoinCount++] = i;
            }
        }
        if (listJoinCount == 0) return rawRows;

        // Use LinkedHashMap to preserve order
        LinkedHashMap<Object, T> seen = new LinkedHashMap<>();
        // For each entity ID, keep the mutable lists for each list-join param index
        LinkedHashMap<Object, List[]> accumulators = new LinkedHashMap<>();

        for (T row : rawRows) {
            Object id;
            try {
                id = idGetter.invoke(row);
            } catch (Throwable e) {
                throw new RuntimeException("Failed to read entity ID during collection-join merge", e);
            }

            if (!seen.containsKey(id)) {
                seen.put(id, row);
                // Extract the mutable lists from this first-seen row
                List[] lists = new List[listJoinCount];
                for (int k = 0; k < listJoinCount; k++) {
                    int pi = listJoinIndices[k];
                    lists[k] = getListField(row, metadata, pi);
                }
                accumulators.put(id, lists);
            } else {
                // Merge: add elements from this row's lists into the accumulated lists
                List[] lists = accumulators.get(id);
                for (int k = 0; k < listJoinCount; k++) {
                    int pi = listJoinIndices[k];
                    List incoming = getListField(row, metadata, pi);
                    if (incoming != null) {
                        lists[k].addAll(incoming);
                    }
                }
            }
        }

        // Rebuild entities with finalized (possibly immutable) lists
        if (metadata.isRecord()) {
            // For records: reconstruct each with merged list args
            List<T> result = new ArrayList<>(seen.size());
            for (var entry : seen.entrySet()) {
                T first = entry.getValue();
                List[] lists = accumulators.get(entry.getKey());
                try {
                    // Read all constructor args from the first row, then replace list-join slots
                    Object[] args = extractRecordArgs(first, metadata);
                    for (int k = 0; k < listJoinCount; k++) {
                        args[listJoinIndices[k]] = List.copyOf(lists[k]);
                    }
                    result.add((T) metadata.constructor().invokeWithArguments(args));
                } catch (Throwable e) {
                    throw new RuntimeException("Failed to reconstruct entity during collection-join merge", e);
                }
            }
            return result;
        } else {
            // For POJO classes: use direct Field reflection — more reliable than MethodHandle
            // for generic List<T> fields, which can cause type-mismatch issues at invoke time.
            List<T> result = new ArrayList<>(seen.size());
            for (var entry : seen.entrySet()) {
                T first = entry.getValue();
                List[] lists = accumulators.get(entry.getKey());
                for (int k = 0; k < listJoinCount; k++) {
                    int pi = listJoinIndices[k];
                    JoinInfo ji = joins[pi];
                    try {
                        java.lang.reflect.Field field = ji.getJoinField();
                        List<?> finalList = new ArrayList<>(lists[k]);
                        if (field != null) {
                            field.set(first, finalList);
                        } else {
                            // fallback to MethodHandle setter
                            MethodHandle setter = paramSetters[pi];
                            if (setter != null) setter.invoke(first, finalList);
                        }
                    } catch (Throwable e) {
                        throw new RuntimeException("Failed to set merged join list on entity field at index " + pi, e);
                    }
                }
                result.add(first);
            }
            return result;
        }
    }

    /** Reads the list stored in a collection-join param slot from a mapped entity instance. */
    @SuppressWarnings("unchecked")
    private static <T> List<Object> getListField(T entity, EntityMetadata<T> metadata, int paramIndex) {
        if (metadata.isRecord()) {
            try {
                java.lang.reflect.RecordComponent[] components = entity.getClass().getRecordComponents();
                if (paramIndex < components.length) {
                    Object val = components[paramIndex].getAccessor().invoke(entity);
                    if (val instanceof List<?> l) return new ArrayList<>(l);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to read list join from record component " + paramIndex, e);
            }
            return new ArrayList<>();
        } else {
            // For POJO classes: use direct Field reflection for reliable generic List access
            JoinInfo ji = metadata.joinInfos()[paramIndex];
            if (ji != null) {
                java.lang.reflect.Field field = ji.getJoinField();
                if (field != null) {
                    try {
                        Object val = field.get(entity);
                        if (val instanceof List<?> l) return new ArrayList<>(l);
                        return new ArrayList<>(); // field is null (no contacts on this row)
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException("Failed to read list join field at index " + paramIndex, e);
                    }
                }
                // fallback: MethodHandle getter
                if (ji.getFieldGetter() != null) {
                    try {
                        Object val = ji.getFieldGetter().invoke(entity);
                        if (val instanceof List<?> l) return new ArrayList<>(l);
                    } catch (Throwable e) {
                        throw new RuntimeException("Failed to read list join via getter at index " + paramIndex, e);
                    }
                }
            }
            return new ArrayList<>();
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
}

