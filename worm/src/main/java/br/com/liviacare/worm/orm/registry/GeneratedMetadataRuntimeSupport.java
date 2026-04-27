package br.com.liviacare.worm.orm.registry;

import br.com.liviacare.worm.api.iBaseEntity;
import br.com.liviacare.worm.orm.converter.ConverterRegistry;
import br.com.liviacare.worm.orm.dialect.SqlDialect;
import br.com.liviacare.worm.orm.mapping.ColumnConverter;
import br.com.liviacare.worm.orm.mapping.EntityBinder;
import br.com.liviacare.worm.orm.mapping.ParamBinder;
import br.com.liviacare.worm.orm.sql.WritePlan;
import br.com.liviacare.worm.util.AliasUtils;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Runtime helper used by APT-generated metadata factories for the initial static metadata phase.
 *
 * <p>Factories generated at compile time provide stable structural descriptors
 * (table/columns/property order). This helper materializes method handles and immutable
 * metadata objects without invoking the reflective {@code MetadataBuilder} pipeline.
 */
public final class GeneratedMetadataRuntimeSupport {

    private static final Map<Class<?>, EntityBinder<?>> ENTITY_BINDERS = loadEntityBinders();

    private GeneratedMetadataRuntimeSupport() {
    }

    private static Map<Class<?>, EntityBinder<?>> loadEntityBinders() {
        Map<Class<?>, EntityBinder<?>> discovered = new LinkedHashMap<>();
        ServiceLoader<EntityBinder> loader = ServiceLoader.load(EntityBinder.class);
        for (EntityBinder<?> binder : loader) {
            Class<?> entityClass = resolveEntityBinderType(binder.getClass());
            if (entityClass != null) {
                discovered.putIfAbsent(entityClass, binder);
            }
        }
        return Map.copyOf(discovered);
    }

    private static Class<?> resolveEntityBinderType(Class<?> binderClass) {
        for (Type itf : binderClass.getGenericInterfaces()) {
            if (!(itf instanceof ParameterizedType pt)) {
                continue;
            }
            if (!(pt.getRawType() instanceof Class<?> raw) || raw != EntityBinder.class) {
                continue;
            }
            Type arg = pt.getActualTypeArguments()[0];
            if (arg instanceof Class<?> entityType) {
                return entityType;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static <T> EntityBinder<T> findBinderForEntity(Class<T> entityClass) {
        return (EntityBinder<T>) ENTITY_BINDERS.get(entityClass);
    }

    public record PropertyDescriptor(
            String propertyName,
            String columnName,
            String label,
            Class<?> propertyType,
            Type genericType,
            boolean id,
            boolean createdBy,
            boolean createdAt,
            boolean updatedAt,
            boolean active,
            boolean deletedAt,
            boolean version,
            boolean activeDefaultValue
    ) {
    }

    public record EntityOptions(
            String module,
            boolean tracked,
            String createdByColumn,
            String createdAtColumn,
            String updatedAtColumn,
            String activeColumn,
            boolean activeDefaultValue,
            String deletedAtColumn,
            String versionColumn
    ) {
        public static EntityOptions defaults(String module, boolean tracked) {
            return new EntityOptions(module, tracked, null, null, null, null, true, null, null);
        }

        boolean hasActive() {
            return activeColumn != null;
        }

        boolean hasDeletedAt() {
            return deletedAtColumn != null;
        }

        boolean hasVersion() {
            return versionColumn != null;
        }
    }

    public record JoinDescriptor(
            String fieldName,
            Class<?> joinClass,
            String table,
            String alias,
            String on,
            br.com.liviacare.worm.annotation.mapping.DbJoin.Type type
    ) {
    }

    public static <T> EntityMetadata<T> buildEntityMetadata(
            Class<T> entityClass,
            String tableName,
            PropertyDescriptor[] descriptors,
            JoinDescriptor[] joins,
            SqlDialect dialect,
            ConverterRegistry converterRegistry,
            EntityOptions options
    ) {
        return buildEntityMetadata(entityClass, tableName, descriptors, joins, dialect, converterRegistry, options, null);
    }

    public static <T> EntityMetadata<T> buildEntityMetadata(
            Class<T> entityClass,
            String tableName,
            PropertyDescriptor[] descriptors,
            JoinDescriptor[] joins,
            SqlDialect dialect,
            ConverterRegistry converterRegistry,
            EntityOptions options,
            String idSelectSql
    ) {
        return buildMetadataInternal(entityClass, tableName, descriptors, joins, dialect, converterRegistry, options, idSelectSql);
    }

    public static <T> EntityMetadata<T> buildSimpleEntityMetadata(
            Class<T> entityClass,
            String tableName,
            PropertyDescriptor[] descriptors,
            SqlDialect dialect,
            ConverterRegistry converterRegistry,
            EntityOptions options
    ) {
        return buildSimpleEntityMetadata(entityClass, tableName, descriptors, dialect, converterRegistry, options, null);
    }

    public static <T> EntityMetadata<T> buildSimpleEntityMetadata(
            Class<T> entityClass,
            String tableName,
            PropertyDescriptor[] descriptors,
            SqlDialect dialect,
            ConverterRegistry converterRegistry,
            EntityOptions options,
            String idSelectSql
    ) {
        return buildMetadataInternal(entityClass, tableName, descriptors, new JoinDescriptor[0], dialect, converterRegistry, options, idSelectSql);
    }

    private static <T> EntityMetadata<T> buildMetadataInternal(
            Class<T> entityClass,
            String tableName,
            PropertyDescriptor[] descriptors,
            JoinDescriptor[] joins,
            SqlDialect dialect,
            ConverterRegistry converterRegistry,
            EntityOptions options,
            String idSelectSql
    ) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(entityClass, MethodHandles.lookup());
            boolean isRecord = entityClass.isRecord();
            if (isRecord && joins.length > 0) {
                throw new IllegalStateException("Generated to-one join metadata currently supports class entities only: " + entityClass.getName());
            }

            int scalarCount = descriptors.length;
            int size = scalarCount + joins.length;
            String[] paramColumnLabels = new String[size];
            Class<?>[] paramTypes = new Class<?>[size];
            Type[] paramGenericTypes = new Type[size];
            ColumnConverter[] paramConverters = new ColumnConverter[size];
            MethodHandle[] paramSetters = new MethodHandle[size];
            JoinInfo[] joinInfos = new JoinInfo[size];

            List<String> selectColumns = new ArrayList<>(scalarCount);
            List<String> selectLabels = new ArrayList<>(scalarCount);
            Class<?>[] selectTypes = new Class<?>[scalarCount];
            MethodHandle[] selectGetters = new MethodHandle[scalarCount];
            MethodHandle[] classSetters = new MethodHandle[scalarCount];
            List<String> insertableColumns = new ArrayList<>(scalarCount);
            List<String> updatableColumns = new ArrayList<>(scalarCount);
            Map<String, Integer> columnIndex = new HashMap<>();

            String idColumn = null;
            MethodHandle idGetter = null;
            MethodHandle versionGetter = null;
            MethodHandle versionSetter = null;

            Constructor<T> noArgCtor = null;
            if (!isRecord) {
                noArgCtor = entityClass.getDeclaredConstructor();
                noArgCtor.setAccessible(true);
            }

            Class<?>[] recordCtorTypes = new Class<?>[scalarCount];
            for (int i = 0; i < scalarCount; i++) {
                PropertyDescriptor d = descriptors[i];
                paramColumnLabels[i] = d.label();
                paramTypes[i] = d.propertyType();
                paramGenericTypes[i] = d.genericType();
                paramConverters[i] = resolveConverter(d.propertyType(), d.genericType(), converterRegistry);
                selectColumns.add(d.columnName());
                selectLabels.add(d.label());
                selectTypes[i] = d.propertyType();
                columnIndex.put(d.label(), i);
                recordCtorTypes[i] = d.propertyType();

                Field field = entityClass.getDeclaredField(d.propertyName());
                field.setAccessible(true);
                MethodHandle getter = lookup.unreflectGetter(field);
                selectGetters[i] = getter;

                if (d.id()) {
                    idColumn = d.columnName();
                    idGetter = getter;
                }

                insertableColumns.add(d.columnName());
                if (!d.id() && !d.createdAt() && !d.createdBy() && !d.version()) {
                    updatableColumns.add(d.columnName());
                }

                if (!isRecord) {
                    MethodHandle setter = lookup.unreflectSetter(field);
                    paramSetters[i] = setter;
                    classSetters[i] = setter;
                    if (d.version()) {
                        versionGetter = getter;
                        versionSetter = setter;
                    }
                } else if (d.version()) {
                    versionGetter = getter;
                }
            }

            String mainAlias;
            if (joins.length == 0) {
                mainAlias = null;
            } else {
                if (tableName != null) mainAlias = AliasUtils.defaultMainAlias(tableName);
                else mainAlias = AliasUtils.defaultMainAlias(AliasUtils.entityTableName(entityClass));
            }

            // Normalize JoinDescriptor ON clauses: replace references to the generated
            // entity class alias (e.g. MyEntity) with the table-derived mainAlias so
            // hand-written generated JoinDescriptor.on strings remain valid.
            GeneratedMetadataRuntimeSupport.JoinDescriptor[] normalizedJoins = new GeneratedMetadataRuntimeSupport.JoinDescriptor[joins.length];
            String entitySimple = entityClass.getSimpleName();
            String entityDecap = Character.toLowerCase(entitySimple.charAt(0)) + entitySimple.substring(1);
            for (int i = 0; i < joins.length; i++) {
                GeneratedMetadataRuntimeSupport.JoinDescriptor jd = joins[i];
                String on = jd.on();
                if (on != null && mainAlias != null) {
                    // Replace both capitalized and decapitalized occurrences of the entity class token
                    on = on.replace(entitySimple + ".", mainAlias + ".");
                    on = on.replace(entityDecap + ".", mainAlias + ".");
                }
                // Enforce table-derived alias for join descriptors so generated factories
                // using relation/class-based aliases are normalized to the table name.
                String normAlias = AliasUtils.defaultMainAlias(jd.table());
                // Also replace any occurrences of the original join alias (e.g. "department.")
                // with the normalized table-derived alias to keep the ON clause consistent.
                if (on != null && jd.alias() != null && !jd.alias().isBlank()) {
                    on = on.replace(jd.alias() + ".", normAlias + ".");
                }
                normalizedJoins[i] = new GeneratedMetadataRuntimeSupport.JoinDescriptor(
                        jd.fieldName(), jd.joinClass(), jd.table(), normAlias, on, jd.type());
            }
            for (int i = 0; i < normalizedJoins.length; i++) {
                JoinDescriptor jd = normalizedJoins[i];
                int paramIndex = scalarCount + i;
                JoinInfo joinInfo = inspectJoin(jd, lookup);
                joinInfos[paramIndex] = joinInfo;
                paramColumnLabels[paramIndex] = null;
                paramTypes[paramIndex] = jd.joinClass();
                paramGenericTypes[paramIndex] = jd.joinClass();
                paramConverters[paramIndex] = raw -> raw;

                Field joinField = entityClass.getDeclaredField(jd.fieldName());
                joinField.setAccessible(true);
                MethodHandle setter = lookup.unreflectSetter(joinField);
                MethodHandle getter = lookup.unreflectGetter(joinField);
                paramSetters[paramIndex] = setter;
                joinInfo.fieldGetter = getter;
                joinInfo.joinField = joinField;

                for (String joinColumn : joinInfo.joinColumnNames) {
                    String label = joinInfo.alias + "_" + joinColumn;
                    joinInfo.resultLabels.add(label);
                }
            }

            if (idColumn == null || idGetter == null) {
                throw new IllegalStateException("Generated metadata missing @DbId mapping for " + entityClass.getName());
            }

            MethodHandle constructor = isRecord
                    ? lookup.findConstructor(entityClass, MethodType.methodType(void.class, recordCtorTypes))
                    : lookup.unreflectConstructor(noArgCtor);

            MethodHandle constructorSpreader = null;
            if (isRecord && constructor != null && scalarCount > 0) {
                try {
                    constructorSpreader = constructor.asSpreader(Object[].class, scalarCount);
                } catch (IllegalArgumentException ignored) {
                }
            }

            MethodHandle createdHook = buildLifecycleHook(entityClass, "created");
            MethodHandle updatedHook = buildLifecycleHook(entityClass, "updated");

            WritePlan insertWritePlan = buildInsertWritePlan(descriptors, insertableColumns, idColumn, idGetter,
                    selectGetters, selectTypes, options, buildInsertSql(tableName, insertableColumns), createdHook,
                    entityClass, dialect);
            WritePlan updateWritePlan = buildUpdateWritePlan(descriptors, updatableColumns, idGetter, versionGetter,
                    selectGetters, selectTypes, buildUpdateSql(tableName, idColumn, updatableColumns, options.versionColumn()),
                    options.hasVersion(), updatedHook, entityClass, dialect);

            EntityMetadata.Builder<T> b = new EntityMetadata.Builder<>();
            b.entityClass = entityClass;
            b.isRecord = isRecord;
            b.tableName = tableName;
            b.idColumnName = idColumn;
            b.idGetter = idGetter;
            b.paramColumnLabels = paramColumnLabels;
            b.paramTypes = paramTypes;
            b.paramGenericTypes = paramGenericTypes;
            b.paramConverters = paramConverters;
            b.paramSetters = paramSetters;
            b.joinInfos = joinInfos;
            b.selectColumns = selectColumns;
            b.selectLabels = selectLabels;
            b.selectTypes = selectTypes;
            b.selectGetters = selectGetters;
            b.classSetters = classSetters;
            b.jsonColumns = new HashSet<>();
            b.insertableColumns = insertableColumns;
            b.updatableColumns = updatableColumns;
            b.constructor = constructor;
            b.constructorSpreader = constructorSpreader;
            b.insertWritePlan = insertWritePlan;
            b.updateWritePlan = updateWritePlan;
            b.binder = findBinderForEntity(entityClass);
            b.selectSql = buildSelectSql(tableName, descriptors, normalizedJoins, mainAlias);
            b.idSelectSql = idSelectSql != null && !idSelectSql.isBlank()
                    ? idSelectSql
                    : buildIdSelectSql(tableName, idColumn);
            b.countSql = "SELECT COUNT(*) FROM " + tableName;
            b.insertSql = buildInsertSql(tableName, insertableColumns);
            b.updateSql = buildUpdateSql(tableName, idColumn, updatableColumns, options.versionColumn());
            b.upsertSql = null;
            b.deleteSql = "DELETE FROM " + tableName + " WHERE " + idColumn + " = ?";
            b.softDeleteSql = buildSoftDeleteSql(tableName, idColumn, options);
            b.hasActive = options.hasActive();
            b.activeColumn = options.activeColumn();
            b.activeDefaultValue = options.activeDefaultValue();
            b.hasDeletedAt = options.hasDeletedAt();
            b.deletedAtColumn = options.deletedAtColumn();
            b.createdByColumn = Optional.ofNullable(options.createdByColumn());
            b.createdAtColumn = Optional.ofNullable(options.createdAtColumn());
            b.updatedAtColumn = Optional.ofNullable(options.updatedAtColumn());
            b.hasVersion = options.hasVersion();
            b.versionColumn = options.versionColumn();
            b.versionGetter = versionGetter;
            b.versionSetter = versionSetter;
            b.defaultOrderBy = descriptors[0].label();
            b.module = options.module();
            b.tracked = options.tracked();
            b.columnIndex = columnIndex;

            EntityMetadata<T> metadata = new EntityMetadata<>(b);
            if (dialect != null) {
                b.upsertSql = dialect.buildUpsertSql(metadata);
                metadata = new EntityMetadata<>(b);
            }
            return metadata;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to build generated metadata for " + entityClass.getName(), e);
        }
    }

    private static ColumnConverter resolveConverter(Class<?> propertyType, Type genericType, ConverterRegistry converterRegistry) {
        if (converterRegistry != null && converterRegistry.hasConverter(propertyType)) {
            return raw -> converterRegistry.fromDatabase(raw, propertyType);
        }
        return ConverterFactory.getConverter(propertyType, genericType);
    }

    private static String buildIdSelectSql(String tableName, String idColumn) {
        String alias = AliasUtils.defaultMainAlias(tableName);
        return "SELECT " + alias + ".* FROM " + tableName + " " + alias + " WHERE " + alias + "." + idColumn + " = ?";
    }

    private static String buildSelectSql(String table, PropertyDescriptor[] descriptors, JoinDescriptor[] joins, String mainAlias) {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT ");
        for (int i = 0; i < descriptors.length; i++) {
            if (i > 0) sb.append(", ");
            String qualifier = mainAlias == null ? table : mainAlias;
            sb.append(qualifier).append('.').append(descriptors[i].columnName()).append(" AS ").append(descriptors[i].label());
        }
        for (JoinDescriptor join : joins) {
            JoinInfo info = inspectJoinColumns(join.joinClass());
            for (String joinColumn : info.joinColumnNames) {
                sb.append(", ").append(join.alias()).append('.').append(joinColumn)
                        .append(" AS ").append(join.alias()).append('_').append(joinColumn);
            }
        }
        sb.append(" FROM ").append(table);
        if (mainAlias != null) {
            sb.append(' ').append(mainAlias);
            for (JoinDescriptor join : joins) {
                sb.append(' ').append(join.type().name()).append(" JOIN ")
                        .append(join.table()).append(' ').append(join.alias())
                        .append(" ON ").append(join.on());
            }
        }
        return sb.toString();
    }

    private static JoinInfo inspectJoin(JoinDescriptor descriptor, MethodHandles.Lookup rootLookup) throws Exception {
        Class<?> joinClass = descriptor.joinClass();
        MethodHandles.Lookup joinLookup = MethodHandles.privateLookupIn(joinClass, rootLookup);
        boolean joinIsRecord = joinClass.isRecord();

        List<String> colNames = new ArrayList<>();
        List<Class<?>> colTypes = new ArrayList<>();
        List<MethodHandle> handles = new ArrayList<>();
        List<ColumnConverter> converters = new ArrayList<>();

        if (joinIsRecord) {
            var components = joinClass.getRecordComponents();
            Class<?>[] ctorTypes = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                var rc = components[i];
                String col = resolveJoinColumnName(rc.getName(), rc.getAccessor().getAnnotation(br.com.liviacare.worm.annotation.mapping.DbColumn.class));
                colNames.add(col);
                colTypes.add(rc.getType());
                handles.add(joinLookup.unreflect(rc.getAccessor()));
                converters.add(ConverterFactory.getConverter(rc.getType(), rc.getGenericType()));
                ctorTypes[i] = rc.getType();
            }
            MethodHandle ctor = joinLookup.findConstructor(joinClass, MethodType.methodType(void.class, ctorTypes));
            JoinInfo info = new JoinInfo(joinClass, true, ctor, null, handles.toArray(MethodHandle[]::new), colNames, colTypes,
                    converters.toArray(ColumnConverter[]::new));
            info.table = descriptor.table();
            info.alias = descriptor.alias();
            info.on = descriptor.on();
            info.type = descriptor.type();
            return info;
        }

        Constructor<?> ctor = joinClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        for (Field field : joinClass.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            String col = resolveJoinColumnName(field.getName(), field.getAnnotation(br.com.liviacare.worm.annotation.mapping.DbColumn.class));
            colNames.add(col);
            colTypes.add(field.getType());
            handles.add(joinLookup.unreflectSetter(field));
            converters.add(ConverterFactory.getConverter(field.getType(), field.getGenericType()));
        }
        JoinInfo info = new JoinInfo(joinClass, false, joinLookup.unreflectConstructor(ctor), handles.toArray(MethodHandle[]::new), null,
                colNames, colTypes, converters.toArray(ColumnConverter[]::new));
        info.table = descriptor.table();
        info.alias = descriptor.alias();
        info.on = descriptor.on();
        info.type = descriptor.type();
        return info;
    }

    private static JoinInfo inspectJoinColumns(Class<?> joinClass) {
        try {
            // Prefer table-based aliasing. If the joinClass carries @DbTable use it,
            // otherwise fall back to the simpleName lowercased. Cache results to avoid repeated annotation reads.
            String table = JOIN_TABLE_CACHE.computeIfAbsent(joinClass, jc -> {
                var dbTable = jc.getAnnotation(br.com.liviacare.worm.annotation.mapping.DbTable.class);
                if (dbTable != null && !dbTable.value().isBlank()) return dbTable.value();
                return jc.getSimpleName().toLowerCase();
            });
            return inspectJoin(new JoinDescriptor("", joinClass, table, AliasUtils.defaultMainAlias(table), "", br.com.liviacare.worm.annotation.mapping.DbJoin.Type.INNER), MethodHandles.lookup());
        } catch (Exception e) {
            throw new RuntimeException("Failed to inspect generated join columns for " + joinClass.getName(), e);
        }
    }

    // Small cache for joinClass -> resolved table name
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, String> JOIN_TABLE_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static String resolveJoinColumnName(String defaultName, br.com.liviacare.worm.annotation.mapping.DbColumn dbColumn) {
        return dbColumn != null && !dbColumn.value().isBlank() ? dbColumn.value() : defaultName;
    }

    private static String buildInsertSql(String table, List<String> columns) {
        StringBuilder sb = new StringBuilder();
        sb.append("INSERT INTO ").append(table).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(columns.get(i));
        }
        sb.append(") VALUES (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('?');
        }
        sb.append(')');
        return sb.toString();
    }

    private static String buildUpdateSql(String table, String idColumn, List<String> updatableColumns, String versionColumn) {
        StringBuilder sb = new StringBuilder();
        sb.append("UPDATE ").append(table).append(" SET ");
        for (int i = 0; i < updatableColumns.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(updatableColumns.get(i)).append(" = ?");
        }
        if (versionColumn != null) {
            if (!updatableColumns.isEmpty()) {
                sb.append(", ");
            }
            sb.append(versionColumn).append(" = ").append(versionColumn).append(" + 1");
        }
        sb.append(" WHERE ").append(idColumn).append(" = ?");
        if (versionColumn != null) {
            sb.append(" AND ").append(versionColumn).append(" = ?");
        }
        return sb.toString();
    }

    private static String buildSoftDeleteSql(String table, String idColumn, EntityOptions options) {
        if (options.hasActive()) {
            return "UPDATE " + table + " SET " + options.activeColumn() + " = false WHERE " + idColumn + " = ?";
        }
        if (options.hasDeletedAt()) {
            return "UPDATE " + table + " SET " + options.deletedAtColumn() + " = ? WHERE " + idColumn + " = ?";
        }
        return null;
    }

    private static MethodHandle buildLifecycleHook(Class<?> entityClass, String methodName) {
        if (!iBaseEntity.class.isAssignableFrom(entityClass)) return null;
        try {
            return MethodHandles.lookup().findVirtual(iBaseEntity.class, methodName, MethodType.methodType(void.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("Failed to compile iBaseEntity." + methodName + " hook for " + entityClass.getName(), e);
        }
    }

    private static WritePlan buildInsertWritePlan(PropertyDescriptor[] descriptors,
                                                  List<String> insertableColumns,
                                                  String idColumn,
                                                  MethodHandle idGetter,
                                                  MethodHandle[] selectGetters,
                                                  Class<?>[] selectTypes,
                                                  EntityOptions options,
                                                  String sql,
                                                  MethodHandle hookHandle,
                                                  Class<?> entityClass,
                                                  SqlDialect dialect) {
        WritePlan.Slot[] slots = new WritePlan.Slot[insertableColumns.size()];
        for (int i = 0; i < insertableColumns.size(); i++) {
            String column = insertableColumns.get(i);
            if (column.equals(idColumn)) {
                slots[i] = new WritePlan.Slot.Field(idGetter, false, false);
                continue;
            }
            PropertyDescriptor d = findDescriptor(descriptors, column);
            if (d.createdAt() || d.updatedAt()) {
                slots[i] = new WritePlan.Slot.AuditNow(d.propertyType() == LocalDateTime.class);
                continue;
            }
            int idx = descriptorIndex(descriptors, column);
            if (options.hasActive() && column.equals(options.activeColumn())) {
                slots[i] = new WritePlan.Slot.ActiveDefault(selectGetters[idx], options.activeDefaultValue(), d.propertyType() == boolean.class);
            } else {
                slots[i] = new WritePlan.Slot.Field(selectGetters[idx], false, d.propertyType().isEnum());
            }
        }
        return createWritePlan(sql, slots, hookHandle, false, entityClass, dialect);
    }

    private static WritePlan buildUpdateWritePlan(PropertyDescriptor[] descriptors,
                                                  List<String> updatableColumns,
                                                  MethodHandle idGetter,
                                                  MethodHandle versionGetter,
                                                  MethodHandle[] selectGetters,
                                                  Class<?>[] selectTypes,
                                                  String sql,
                                                  boolean hasVersion,
                                                  MethodHandle hookHandle,
                                                  Class<?> entityClass,
                                                  SqlDialect dialect) {
        WritePlan.Slot[] slots = new WritePlan.Slot[updatableColumns.size() + 1 + (hasVersion ? 1 : 0)];
        int out = 0;
        for (String column : updatableColumns) {
            PropertyDescriptor d = findDescriptor(descriptors, column);
            if (d.updatedAt()) {
                slots[out++] = new WritePlan.Slot.AuditNow(d.propertyType() == LocalDateTime.class);
                continue;
            }
            int idx = descriptorIndex(descriptors, column);
            slots[out++] = new WritePlan.Slot.Field(selectGetters[idx], false, selectTypes[idx].isEnum());
        }
        slots[out++] = new WritePlan.Slot.Field(idGetter, false, false);
        if (hasVersion) {
            slots[out] = new WritePlan.Slot.Field(versionGetter, false, false);
        }
        return createWritePlan(sql, slots, hookHandle, hasVersion, entityClass, dialect);
    }

    private static WritePlan createWritePlan(String sql,
                                             WritePlan.Slot[] slots,
                                             MethodHandle hookHandle,
                                             boolean hasVersion,
                                             Class<?> entityClass,
                                             SqlDialect dialect) {
        if (dialect != null) {
            ParamBinder binder = dialect.createParamBinder(entityClass, sql, slots, hasVersion);
            if (binder != null) {
                return new WritePlan(sql, slots, binder, hookHandle, hasVersion);
            }
        }
        return WritePlan.compiled(sql, slots, hookHandle, hasVersion);
    }

    private static PropertyDescriptor findDescriptor(PropertyDescriptor[] descriptors, String column) {
        for (PropertyDescriptor descriptor : descriptors) {
            if (descriptor.columnName().equals(column)) return descriptor;
        }
        throw new IllegalStateException("No generated PropertyDescriptor found for column '" + column + "'");
    }

    private static int descriptorIndex(PropertyDescriptor[] descriptors, String column) {
        for (int i = 0; i < descriptors.length; i++) {
            if (descriptors[i].columnName().equals(column)) return i;
        }
        throw new IllegalStateException("No generated descriptor index found for column '" + column + "'");
    }
}
