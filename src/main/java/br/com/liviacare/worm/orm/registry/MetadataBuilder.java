package br.com.liviacare.worm.orm.registry;

import br.com.liviacare.worm.annotation.audit.*;
import br.com.liviacare.worm.annotation.mapping.*;
import br.com.liviacare.worm.orm.mapping.ColumnConverter;
import br.com.liviacare.worm.orm.sql.SqlConstants;
import br.com.liviacare.worm.util.AliasUtils;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class MetadataBuilder<T> {

    private final Class<T> entityClass;
    private final br.com.liviacare.worm.orm.dialect.SqlDialect dialect;
    private final br.com.liviacare.worm.orm.converter.ConverterRegistry converterRegistry;
    private final MethodHandles.Lookup lookup;
    private final MethodHandles.Lookup privateLookup;
    private final boolean isRecord;
    private final String tableName;

    private final List<String>          selectExprs        = new ArrayList<>();
    private final List<String>          selectLabelsList   = new ArrayList<>();
    private final List<String>          selectCols         = new ArrayList<>();
    private final List<Class<?>>        selectTypesList    = new ArrayList<>();
    private final List<MethodHandle>    selectGettersList  = new ArrayList<>();
    private final List<MethodHandle>    classSettersList   = new ArrayList<>();
    private final List<String>          insertable         = new ArrayList<>();
    private final List<String>          updatable          = new ArrayList<>();
    private final List<String>          paramLabels        = new ArrayList<>();
    private final List<Class<?>>        paramTypesList     = new ArrayList<>();
    private final List<Type>            paramGenericList   = new ArrayList<>();
    private final List<ColumnConverter> paramConverterList = new ArrayList<>();
    private final List<MethodHandle>    paramSetterList    = new ArrayList<>();
    private final List<JoinInfo>        joinInfoList       = new ArrayList<>();
    private final List<String>          orderByRaw         = new ArrayList<>();
    private final Set<String>           jsonCols           = new HashSet<>();

    private MethodHandle constructorHandle;
    private MethodHandle idGetterHandle;
    private String       idColumnName;

    private final String mainAlias;
    private final Set<String> usedAliasesLowerCase = new HashSet<>();

    MetadataBuilder(Class<T> entityClass,
                    br.com.liviacare.worm.orm.dialect.SqlDialect dialect,
                    br.com.liviacare.worm.orm.converter.ConverterRegistry converterRegistry)
            throws IllegalAccessException {
        this.entityClass       = entityClass;
        this.dialect           = dialect;
        this.converterRegistry = converterRegistry;
        this.lookup            = MethodHandles.lookup();
        this.privateLookup     = MethodHandles.privateLookupIn(entityClass, lookup);
        this.isRecord          = entityClass.isRecord();

        DbTable dbTable = entityClass.getAnnotation(DbTable.class);
        this.tableName = dbTable != null ? dbTable.value() : null;
        this.mainAlias = AliasUtils.defaultMainAlias(entityClass);
        this.usedAliasesLowerCase.add(this.mainAlias.toLowerCase());
    }

    EntityMetadata<T> build() throws NoSuchMethodException, IllegalAccessException {
        if (tableName == null) return null;

        if (isRecord) processRecordComponents();
        else          processFields();

        if (idColumnName == null) {
            // Fallback: if @DbId was not found, try to locate a field named 'id'
            logIdWarning();
        }

        Field[] allFields = getAllFields(entityClass).toArray(new Field[0]);
        return assembleFinal(allFields);
    }

    private void logIdWarning() {
        // Internal debug placeholder — no @DbId found on entity
    }

    private void processRecordComponents() throws NoSuchMethodException, IllegalAccessException {
        RecordComponent[] components = entityClass.getRecordComponents();
        for (RecordComponent comp : components) {
            DbJoin dbJoin = comp.getAnnotation(DbJoin.class);
            if (dbJoin != null) processJoinComp(comp, dbJoin);
            else                processRegularComp(comp);
        }
        Class<?>[] types = Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
        constructorHandle = privateLookup.findConstructor(entityClass, MethodType.methodType(void.class, types));
    }

    private void processJoinComp(RecordComponent comp, DbJoin ann) throws IllegalAccessException {
        Class<?> joinClass = extractJoinClass(comp.getGenericType());
        validateJoinClass(joinClass, comp.getName());
        JoinInfo ji = inspectJoinClass(joinClass);
        ji.isList = isCollectionType(comp.getType());
        applyJoinAnnotation(ji, ann, comp.getName(), ji.isList);
        ji.fieldGetter = privateLookup.unreflect(comp.getAccessor());
        addJoinSelectExprs(ji);
        captureOrderByForJoin(comp.getAnnotation(OrderBy.class), ji);
        addJoinParam(comp.getType(), comp.getGenericType(), null);
        joinInfoList.add(ji);
    }

    private void processRegularComp(RecordComponent comp) throws IllegalAccessException {
        boolean hasDbCol       = comp.isAnnotationPresent(DbColumn.class);
        DbColumn dbColAnn      = comp.getAnnotation(DbColumn.class);
        String  annotatedValue = hasDbCol ? dbColAnn.value() : null;
        String  annotatedExpr  = hasDbCol ? dbColAnn.expr()  : null;

        if (hasDbCol && annotatedExpr != null && !annotatedExpr.isBlank()) {
            String alias = (annotatedValue != null && !annotatedValue.isBlank()) ? annotatedValue : comp.getName();
            validateExprAlias(comp.getName(), alias);
            addSelectEntry(annotatedExpr + " AS " + alias, alias, comp.getType(), privateLookup.unreflect(comp.getAccessor()), null);
            addRegularParam(alias, comp.getType(), comp.getGenericType(), null);
            insertable.add(alias);
            if (!comp.isAnnotationPresent(DbId.class) && !isAuditing(comp)) updatable.add(alias);
            if (dbColAnn != null && dbColAnn.json()) jsonCols.add(alias);
        } else {
            String col = resolveColumnName(comp);
            addSelectEntry(mainAlias + "." + col + " AS " + col, col, comp.getType(), privateLookup.unreflect(comp.getAccessor()), null);
            addRegularParam(col, comp.getType(), comp.getGenericType(), null);
            insertable.add(col);
            if (!comp.isAnnotationPresent(DbId.class) && !isAuditing(comp)) updatable.add(col);
            
            if (comp.isAnnotationPresent(DbId.class)) {
                this.idColumnName = col;
                this.idGetterHandle = privateLookup.unreflect(comp.getAccessor());
            } else if (this.idColumnName == null && "id".equals(comp.getName())) {
                // Convention: if the field is named 'id' and no @DbId has been found yet, treat it as the ID
                this.idColumnName = col;
                this.idGetterHandle = privateLookup.unreflect(comp.getAccessor());
            }

            if (dbColAnn != null && dbColAnn.json()) jsonCols.add(col);
            captureOrderByForColumn(comp.getAnnotation(OrderBy.class), col);
        }
    }

    private void processFields() throws NoSuchMethodException, IllegalAccessException {
        for (Field f : getAllFields(entityClass)) {
            DbJoin dbJoin = f.getAnnotation(DbJoin.class);
            if (dbJoin != null) processJoinField(f, dbJoin);
            else                processRegularField(f);
        }
        constructorHandle = privateLookup.findConstructor(entityClass, MethodType.methodType(void.class));
    }

    private void processJoinField(Field f, DbJoin ann) throws IllegalAccessException {
        f.setAccessible(true);
        Class<?> joinClass = extractJoinClass(f.getGenericType());
        validateJoinClass(joinClass, f.getName());
        JoinInfo ji = inspectJoinClass(joinClass);
        ji.isList = isCollectionType(f.getType());
        applyJoinAnnotation(ji, ann, f.getName(), ji.isList);
        ji.fieldGetter = privateLookup.unreflectGetter(f);
        ji.joinField = f; // store raw Field for direct reflection on POJO merge
        addJoinSelectExprs(ji);
        captureOrderByForJoin(f.getAnnotation(OrderBy.class), ji);
        addJoinParam(f.getType(), f.getGenericType(), privateLookup.unreflectSetter(f));
        joinInfoList.add(ji);
    }

    private void processRegularField(Field f) throws IllegalAccessException {
        f.setAccessible(true);
        String col = resolveColumnName(f);
        DbColumn dbColAnn = f.getAnnotation(DbColumn.class);
        addSelectEntry(mainAlias + "." + col + " AS " + col, col, f.getType(),
                privateLookup.unreflectGetter(f), privateLookup.unreflectSetter(f));
        addRegularParam(col, f.getType(), f.getGenericType(), privateLookup.unreflectSetter(f));
        insertable.add(col);
        if (!f.isAnnotationPresent(DbId.class) && !isAuditing(f)) updatable.add(col);
        
        if (f.isAnnotationPresent(DbId.class)) {
            this.idColumnName = col;
            this.idGetterHandle = privateLookup.unreflectGetter(f);
        } else if (this.idColumnName == null && "id".equals(f.getName())) {
            // Convention: if the field is named 'id' and no @DbId has been found yet, treat it as the ID
            this.idColumnName = col;
            this.idGetterHandle = privateLookup.unreflectGetter(f);
        }

        if (dbColAnn != null && dbColAnn.json()) jsonCols.add(col);
        captureOrderByForColumn(f.getAnnotation(OrderBy.class), col);
    }

    private JoinInfo inspectJoinClass(Class<?> joinClass) {
        try {
            MethodHandles.Lookup jl = MethodHandles.privateLookupIn(joinClass, lookup);
            boolean joinIsRecord = joinClass.isRecord();

            List<String>          colNames  = new ArrayList<>();
            List<Class<?>>        colTypes  = new ArrayList<>();
            List<MethodHandle>    handles   = new ArrayList<>();
            List<ColumnConverter> convs     = new ArrayList<>();

            if (joinIsRecord) {
                for (RecordComponent rc : joinClass.getRecordComponents()) {
                    String jcol = resolveColumnName(rc);
                    colNames.add(jcol);
                    colTypes.add(rc.getType());
                    handles.add(jl.unreflect(rc.getAccessor()));
                    convs.add(ConverterFactory.getConverter(rc.getType(), rc.getGenericType()));
                }
                MethodHandle ctor = jl.findConstructor(joinClass,
                        MethodType.methodType(void.class, colTypes.toArray(Class<?>[]::new)));
                return new JoinInfo(joinClass, true, ctor, null,
                        handles.toArray(MethodHandle[]::new), colNames, colTypes,
                        convs.toArray(ColumnConverter[]::new));
            } else {
                for (Field jf : getAllFields(joinClass)) {
                    jf.setAccessible(true);
                    String jcol = resolveColumnName(jf);
                    colNames.add(jcol);
                    colTypes.add(jf.getType());
                    handles.add(jl.unreflectSetter(jf));
                    convs.add(ConverterFactory.getConverter(jf.getType(), jf.getGenericType()));
                }
                MethodHandle ctor = jl.findConstructor(joinClass, MethodType.methodType(void.class));
                return new JoinInfo(joinClass, false, ctor,
                        handles.toArray(MethodHandle[]::new), null, colNames, colTypes,
                        convs.toArray(ColumnConverter[]::new));
            }
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("Failed to inspect join class: " + joinClass.getName(), e);
        }
    }

    private static final Map<Class<? extends Annotation>, String> AUDIT_DEFAULTS = Map.of(
            DbId.class, "id",
            CreatedAt.class, "created_at",
            UpdatedAt.class, "updated_at",
            DeletedAt.class, "deleted_at",
            Active.class, "active",
            CreatedBy.class, "created_by",
            UpdatedBy.class, "updated_by"
    );

    private String resolveColumnName(RecordComponent comp) {
        DbColumn dbCol = comp.getAnnotation(DbColumn.class);
        if (dbCol != null && !dbCol.value().isBlank()) return dbCol.value();
        
        for (var entry : AUDIT_DEFAULTS.entrySet()) {
            if (comp.isAnnotationPresent(entry.getKey())) {
                String val = annotationValue(comp, entry.getKey());
                return (val != null && !val.isBlank()) ? val : entry.getValue();
            }
        }
        return comp.getName();
    }

    private String resolveColumnName(Field f) {
        DbColumn dbCol = f.getAnnotation(DbColumn.class);
        if (dbCol != null && !dbCol.value().isBlank()) return dbCol.value();

        for (var entry : AUDIT_DEFAULTS.entrySet()) {
            if (f.isAnnotationPresent(entry.getKey())) {
                String val = annotationValue(f, entry.getKey());
                return (val != null && !val.isBlank()) ? val : entry.getValue();
            }
        }
        return f.getName();
    }

    private static String annotationValue(Object annotationHolder, Class<? extends Annotation> annClass) {
        try {
            Annotation ann = (annotationHolder instanceof RecordComponent rc)
                    ? rc.getAnnotation(annClass)
                    : ((Field) annotationHolder).getAnnotation(annClass);
            if (ann == null) return null;
            return (String) ann.annotationType().getMethod("value").invoke(ann);
        } catch (Exception e) {
            return null;
        }
    }

    private void addSelectEntry(String expr, String label, Class<?> type, MethodHandle getter, MethodHandle setter) {
        selectExprs.add(expr);
        selectLabelsList.add(label);
        selectCols.add(label);
        selectTypesList.add(type);
        selectGettersList.add(getter);
        classSettersList.add(setter);
    }

    private void addRegularParam(String label, Class<?> type, Type genericType, MethodHandle setter) {
        paramLabels.add(label);
        paramTypesList.add(type);
        paramGenericList.add(genericType);
        paramConverterList.add(resolveConverter(type, genericType));
        paramSetterList.add(setter);
        joinInfoList.add(null);
    }

    private void addJoinParam(Class<?> type, Type genericType, MethodHandle setter) {
        paramLabels.add(null);
        paramTypesList.add(type);
        paramGenericList.add(genericType);
        paramConverterList.add(raw -> raw);
        paramSetterList.add(setter);
    }

    private void addJoinSelectExprs(JoinInfo ji) {
        for (String jcol : ji.joinColumnNames) {
            String label = ji.alias + "_" + jcol;
            selectExprs.add(ji.alias + "." + jcol + " AS " + label);
            selectLabelsList.add(label);
            ji.resultLabels.add(label);
        }
    }

    private ColumnConverter resolveConverter(Class<?> type, Type genericType) {
        if (converterRegistry != null && converterRegistry.hasConverter(type))
            return raw -> converterRegistry.fromDatabase(raw, type);
        return ConverterFactory.getConverter(type, genericType);
    }

    private void captureOrderByForColumn(OrderBy ob, String col) {
        if (ob == null) return;
        String v = ob.value();
        String token = (v == null || v.isBlank()) ? col : v;
        addOrderByToken(token, ob.direction());
    }

    private void captureOrderByForJoin(OrderBy ob, JoinInfo ji) {
        if (ob == null) return;
        String v = ob.value();
        String token;
        if (v == null || v.isBlank()) {
            if (ji.joinColumnNames.isEmpty()) return;
            token = ji.alias + "." + ji.joinColumnNames.get(0);
        } else if (v.contains(".") || v.contains(" ") || v.contains("(")) {
            token = v;
        } else {
            token = ji.alias + "." + v;
        }
        addOrderByToken(token, ob.direction());
    }

    private void addOrderByToken(String token, OrderBy.Direction dir) {
        String tl = token.toLowerCase(Locale.ROOT);
        orderByRaw.add(tl.contains(" asc") || tl.contains(" desc") ? token : token + " " + dir.name());
    }

    private void validateExprAlias(String compName, String alias) {
        if (!alias.matches("[A-Za-z_][A-Za-z0-9_]*"))
            throw new RuntimeException("@DbColumn alias '" + alias + "' derived from '" + compName + "' is not a valid SQL identifier.");
        if (selectCols.contains(alias))
            throw new RuntimeException("Duplicate column alias '" + alias + "' for entity " + entityClass.getName());
    }

    private static List<Field> getAllFields(Class<?> clazz) {
        Map<String, Field> fields = new LinkedHashMap<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                fields.putIfAbsent(f.getName(), f);
            }
            current = current.getSuperclass();
        }
        return new ArrayList<>(fields.values());
    }

    private static boolean isAuditing(RecordComponent c) {
        return c.isAnnotationPresent(CreatedAt.class) || c.isAnnotationPresent(CreatedBy.class);
    }

    private static boolean isAuditing(Field f) {
        return f.isAnnotationPresent(CreatedAt.class) || f.isAnnotationPresent(CreatedBy.class);
    }

    private static Optional<Field> findField(Field[] fields, Class<? extends Annotation> ann) {
        return Arrays.stream(fields).filter(f -> f.isAnnotationPresent(ann)).findFirst();
    }

    private void applyJoinAnnotation(JoinInfo ji, DbJoin ann, String relationName, boolean isCollection) {
        ji.table = resolveJoinTable(ann, ji);
        String requestedAlias = resolveJoinAlias(ann, ji.table, relationName);
        ji.alias = AliasUtils.ensureUniqueAlias(requestedAlias, usedAliasesLowerCase);
        ji.on = resolveJoinOn(ann, ji, ji.alias, relationName, isCollection);
        if (!ann.on().isBlank() && !ann.alias().isBlank() && !ann.alias().equals(ji.alias)) {
            ji.on = replaceAliasReference(ji.on, ann.alias(), ji.alias);
        }
        if (!ann.on().isBlank()) {
            // Backward compatibility for old docs/samples that used hardcoded main aliases a/a1.
            if (!"a1".equals(ji.alias)) {
                ji.on = replaceAliasReference(ji.on, "a1", mainAlias);
            }
            if (!"a".equals(mainAlias) && !"a".equals(ji.alias)) {
                ji.on = replaceAliasReference(ji.on, "a", mainAlias);
            }
        }
        ji.type = ann.type();
    }

    private String resolveJoinTable(DbJoin ann, JoinInfo ji) {
        if (!ann.table().isBlank()) return ann.table();
        if (!ann.value().isBlank()) return ann.value();
        DbTable joinTable = ji.joinClass.getAnnotation(DbTable.class);
        if (joinTable != null && !joinTable.value().isBlank()) return joinTable.value();
        throw new RuntimeException("@DbJoin on " + entityClass.getName() + " requires table/value or a joined class annotated with @DbTable");
    }

    private static String resolveJoinAlias(DbJoin ann, String table, String relationName) {
        if (!ann.alias().isBlank()) return AliasUtils.sanitizeAlias(ann.alias());
        return AliasUtils.defaultJoinAlias(relationName, table);
    }

    private static String replaceAliasReference(String expression, String fromAlias, String toAlias) {
        if (expression == null || expression.isBlank() || fromAlias == null || fromAlias.isBlank()) return expression;
        if (toAlias == null || toAlias.isBlank() || fromAlias.equals(toAlias)) return expression;
        return expression.replaceAll("\\b" + Pattern.quote(fromAlias) + "\\.", toAlias + ".");
    }

    private String resolveJoinOn(DbJoin ann, JoinInfo ji, String alias, String relationName, boolean isCollection) {
        if (!ann.on().isBlank()) return ann.on();

        String referencedColumn = resolveReferencedColumn(ann);
        if (!ann.localColumn().isBlank()) {
            validateMainEntityColumn(ann.localColumn(), relationName, "localColumn");
            validateJoinColumn(ji, referencedColumn, relationName, "referencedColumn/targetColumn");
            return alias + "." + referencedColumn + " = " + mainAlias + "." + ann.localColumn();
        }

        if (!ann.mappedBy().isBlank()) {
            validateJoinColumn(ji, ann.mappedBy(), relationName, "mappedBy");
            return alias + "." + ann.mappedBy() + " = " + mainAlias + "." + idColumnOrDefault();
        }

        if (isCollection) {
            String inferredMappedBy = inferMappedByFromBackReference(ji.joinClass);
            if (inferredMappedBy == null) {
                inferredMappedBy = singularize(tableName) + "_id";
            }
            return alias + "." + inferredMappedBy + " = " + mainAlias + "." + idColumnOrDefault();
        }

        validateJoinColumn(ji, referencedColumn, relationName, "referencedColumn/targetColumn");
        String inferredLocalColumn = toSnakeCase(relationName) + "_id";
        return alias + "." + referencedColumn + " = " + mainAlias + "." + inferredLocalColumn;
    }

    private static String resolveReferencedColumn(DbJoin ann) {
        if (!ann.targetColumn().isBlank()) return ann.targetColumn();
        return ann.referencedColumn().isBlank() ? "id" : ann.referencedColumn();
    }

    private String inferMappedByFromBackReference(Class<?> joinClass) {
        List<String> candidates = new ArrayList<>();
        if (joinClass.isRecord()) {
            for (RecordComponent rc : joinClass.getRecordComponents()) {
                if (rc.getType().equals(entityClass)) {
                    candidates.add(toSnakeCase(rc.getName()) + "_id");
                }
            }
        } else {
            for (Field field : getAllFields(joinClass)) {
                if (field.getType().equals(entityClass)) {
                    candidates.add(toSnakeCase(field.getName()) + "_id");
                }
            }
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private void validateMainEntityColumn(String column, String relationName, String attribute) {
        if (hasMainEntityColumn(column)) return;
        throw new RuntimeException("@DbJoin(" + attribute + ") on relation '" + relationName + "' in "
                + entityClass.getName() + " references unknown main-entity column '" + column + "'");
    }

    private void validateJoinColumn(JoinInfo ji, String column, String relationName, String attribute) {
        if (ji.joinColumnNames.contains(column)) return;
        throw new RuntimeException("@DbJoin(" + attribute + ") on relation '" + relationName + "' in "
                + entityClass.getName() + " references unknown join column '" + column + "' from "
                + ji.joinClass.getName());
    }

    private boolean hasMainEntityColumn(String column) {
        if (column == null || column.isBlank()) return false;
        if (isRecord) {
            for (RecordComponent comp : entityClass.getRecordComponents()) {
                if (comp.isAnnotationPresent(DbJoin.class)) continue;
                if (column.equals(resolveColumnName(comp))) return true;
            }
            return false;
        }
        for (Field field : getAllFields(entityClass)) {
            if (field.isAnnotationPresent(DbJoin.class)) continue;
            if (column.equals(resolveColumnName(field))) return true;
        }
        return false;
    }

    private String idColumnOrDefault() {
        return (idColumnName == null || idColumnName.isBlank()) ? "id" : idColumnName;
    }

    private static String toSnakeCase(String value) {
        if (value == null || value.isBlank()) return "id";
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static String singularize(String value) {
        if (value == null || value.isBlank()) return "entity";
        if (value.endsWith("ies") && value.length() > 3) return value.substring(0, value.length() - 3) + "y";
        if (value.endsWith("s") && value.length() > 1) return value.substring(0, value.length() - 1);
        return value;
    }

    private EntityMetadata<T> assembleFinal(Field[] allFields)
            throws NoSuchMethodException, IllegalAccessException {

        String normalizedOrderBy = resolveOrderBy();
        List<String> joinClauses = buildJoinClauses();

        Optional<String> createdByCol = findField(allFields, CreatedBy.class)
                .map(f -> resolveColumnName(f));
        Optional<String> createdAtCol = resolveAuditCol(allFields, CreatedAt.class);
        Optional<String> updatedAtCol = resolveAuditCol(allFields, UpdatedAt.class);

        Optional<Field> activeField   = findField(allFields, Active.class);
        Optional<Field> deletedAtF    = findField(allFields, DeletedAt.class);
        boolean hasActive    = activeField.isPresent();
        boolean hasDeletedAt = deletedAtF.isPresent();
        String  activeCol    = hasActive   ? resolveColumnName(activeField.get())    : null;
        String  deletedAtCol = hasDeletedAt ? resolveColumnName(deletedAtF.get()) : null;
        boolean activeDefaultValue = hasActive && activeField.get().getAnnotation(Active.class).defaultValue();

        Optional<Field> versionField = findField(allFields, DbVersion.class);

        String selectSql   = buildSelectSql(joinClauses);
        String countSql    = SqlConstants.SELECT_COUNT_STAR_FROM + tableName;
        String insertSql   = buildInsertSql();
        Optional<String> versionCol = versionField.map(f -> resolveColumnName(f));
        List<String> effectiveUpdatable = new ArrayList<>(updatable);
        versionCol.ifPresent(effectiveUpdatable::remove);
        String updateSql   = buildUpdateSql(versionCol, effectiveUpdatable);
        
        if (idColumnName == null) {
            throw new RuntimeException("Entity " + entityClass.getName() + " must have an ID column (annotate a field with @DbId or name it 'id')");
        }

        String deleteSql   = SqlConstants.DELETE_FROM + tableName + SqlConstants.WHERE + idColumnName + " = " + SqlConstants.PLACEHOLDER;
        String softDelSql  = buildSoftDeleteSql(hasActive, activeCol, hasDeletedAt, deletedAtCol);

        boolean      hasVersion     = versionField.isPresent();
        String       versionColName = versionCol.orElse(null);
        MethodHandle versionGetter  = null;
        MethodHandle versionSetter  = null;
        if (hasVersion) {
            if (isRecord) {
                int vi = selectCols.indexOf(versionColName);
                if (vi >= 0) versionGetter = selectGettersList.get(vi);
            } else {
                Field vf = versionField.get();
                vf.setAccessible(true);
                versionGetter = privateLookup.unreflectGetter(vf);
                versionSetter = privateLookup.unreflectSetter(vf);
            }
        }

        Map<String, Integer> colIndex = new HashMap<>();
        for (int i = 0; i < selectCols.size(); i++) colIndex.put(selectCols.get(i), i);

        EntityMetadata.Builder<T> b = new EntityMetadata.Builder<>();
        b.entityClass        = entityClass;
        b.isRecord           = isRecord;
        b.tableName          = tableName;
        b.idColumnName       = idColumnName;
        b.idGetter           = idGetterHandle;
        b.paramColumnLabels  = paramLabels.toArray(String[]::new);
        b.paramTypes         = paramTypesList.toArray(Class<?>[]::new);
        b.paramGenericTypes  = paramGenericList.toArray(Type[]::new);
        b.paramConverters    = paramConverterList.toArray(ColumnConverter[]::new);
        b.paramSetters       = paramSetterList.toArray(MethodHandle[]::new);
        b.joinInfos          = joinInfoList.toArray(JoinInfo[]::new);
        b.selectColumns      = selectCols;
        b.selectLabels       = selectLabelsList;
        b.selectTypes        = selectTypesList.toArray(Class<?>[]::new);
        b.selectGetters      = selectGettersList.toArray(MethodHandle[]::new);
        b.classSetters       = classSettersList.toArray(MethodHandle[]::new);
        b.jsonColumns        = jsonCols;
        b.insertableColumns  = insertable;
        b.updatableColumns   = effectiveUpdatable;
        b.constructor        = constructorHandle;
        b.selectSql          = selectSql;
        b.countSql           = countSql;
        b.insertSql          = insertSql;
        b.updateSql          = updateSql;
        b.deleteSql          = deleteSql;
        b.softDeleteSql      = softDelSql;
        b.hasActive          = hasActive;
        b.activeColumn       = activeCol;
        b.activeDefaultValue = activeDefaultValue;
        b.hasDeletedAt       = hasDeletedAt;
        b.deletedAtColumn    = deletedAtCol;
        b.createdByColumn    = createdByCol;
        b.createdAtColumn    = createdAtCol;
        b.updatedAtColumn    = updatedAtCol;
        b.hasVersion         = hasVersion;
        b.versionColumn      = versionColName;
        b.versionGetter      = versionGetter;
        b.versionSetter      = versionSetter;
        b.defaultOrderBy     = normalizedOrderBy;
        b.columnIndex        = colIndex;
        // module routing: read from @DbTable(module = "...")
        DbTable dbTableAnn = entityClass.getAnnotation(DbTable.class);
        b.module = (dbTableAnn != null && !dbTableAnn.module().isBlank()) ? dbTableAnn.module() : null;

        if (dialect != null) {
            b.upsertSql = dialect.buildUpsertSql(new EntityMetadata<>(b));
        }

        return new EntityMetadata<>(b);
    }

    private String buildSelectSql(List<String> joinClauses) {
        return SqlConstants.SELECT
                + String.join(SqlConstants.COMMA_SPACE, selectExprs)
                + SqlConstants.FROM + tableName + " " + mainAlias
                + String.join("", joinClauses);
    }

    private String buildInsertSql() {
        return SqlConstants.INSERT_INTO + tableName + " ("
                + String.join(SqlConstants.COMMA_SPACE, insertable)
                + SqlConstants.VALUES_PREFIX
                + insertable.stream().map(c -> SqlConstants.PLACEHOLDER).collect(Collectors.joining(SqlConstants.COMMA_SPACE))
                + ")";
    }

    private String buildUpdateSql(Optional<String> versionCol, List<String> updatableCols) {
        String base = SqlConstants.UPDATE + tableName + SqlConstants.SET
                + updatableCols.stream().map(c -> c + " = " + SqlConstants.PLACEHOLDER).collect(Collectors.joining(SqlConstants.COMMA_SPACE));
        if (versionCol.isPresent()) {
            String vc = versionCol.get();
            return base + SqlConstants.COMMA_SPACE + vc + " = " + vc + " + 1"
                    + SqlConstants.WHERE + idColumnName + " = " + SqlConstants.PLACEHOLDER
                    + SqlConstants.AND   + vc           + " = " + SqlConstants.PLACEHOLDER;
        }
        return base + SqlConstants.WHERE + idColumnName + " = " + SqlConstants.PLACEHOLDER;
    }

    private String buildSoftDeleteSql(boolean hasActive, String activeCol,
                                      boolean hasDeletedAt, String deletedAtCol) {
        if (hasActive)
            return SqlConstants.UPDATE + tableName + SqlConstants.SET + activeCol + " = false"
                    + SqlConstants.WHERE + idColumnName + " = " + SqlConstants.PLACEHOLDER;
        if (hasDeletedAt)
            return SqlConstants.UPDATE + tableName + SqlConstants.SET + deletedAtCol + " = " + SqlConstants.PLACEHOLDER
                    + SqlConstants.WHERE + idColumnName + " = " + SqlConstants.PLACEHOLDER;
        return null;
    }

    private List<String> buildJoinClauses() {
        return joinInfoList.stream()
                .filter(ji -> ji != null && ji.table != null && ji.on != null && !ji.on.isBlank())
                .map(ji -> " " + ji.type.name() + " JOIN " + ji.table + " " + ji.alias + " ON " + ji.on)
                .distinct()
                .collect(Collectors.toList());
    }

    private String resolveOrderBy() {
        if (!orderByRaw.isEmpty()) {
            return orderByRaw.stream()
                    .map(this::normalizeOrderByPart)
                    .collect(Collectors.joining(", "));
        }
        OrderBy classOb = entityClass.getAnnotation(OrderBy.class);
        if (classOb != null && !classOb.value().isBlank()) {
            String cv = classOb.value();
            String tl = cv.toLowerCase(Locale.ROOT);
            return tl.contains(" asc") || tl.contains(" desc") ? cv : cv + " " + classOb.direction().name();
        }
        return null;
    }

    private String normalizeOrderByPart(String part) {
        String ob       = part.trim();
        String[] split  = ob.split("\\s+", 2);
        String token    = split[0];
        String suffix   = split.length > 1 ? " " + split[1] : "";

        if (token.contains("(")) return token + suffix;

        if (token.contains(".")) {
            String[] t    = token.split("\\.", 2);
            String alias  = t[0];
            String col    = t[1];
            boolean valid = (alias.equals(tableName) || alias.equals(mainAlias)) && selectCols.contains(col)
                    || joinInfoList.stream().anyMatch(ji -> ji != null && alias.equals(ji.alias) && ji.joinColumnNames.contains(col));
            if (!valid) throw new RuntimeException("@OrderBy '" + part + "' on " + entityClass.getName() + " references unknown column '" + token + "'");
            return token + suffix;
        }

        if (selectCols.contains(token)) return token + suffix;

        List<JoinInfo> matches = joinInfoList.stream()
                .filter(ji -> ji != null && ji.joinColumnNames.contains(token)).toList();
        if (matches.size() > 1) throw new RuntimeException("Ambiguous @OrderBy '" + token + "' found in multiple joined entities for " + entityClass.getName());
        if (matches.size() == 1) return matches.get(0).alias + "." + token + suffix;

        throw new RuntimeException("@OrderBy '" + token + "' on " + entityClass.getName() + " does not reference any selected column");
    }

    private <A extends Annotation> Optional<String> resolveAuditCol(Field[] fields, Class<A> annClass) {
        return findField(fields, annClass).map(f -> resolveColumnName(f));
    }

    private static boolean isCollectionType(Class<?> type) {
        return List.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type);
    }

    private Class<?> extractJoinClass(Type genericType) {
        if (genericType instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length > 0 && args[0] instanceof Class<?> clazz) {
                return clazz;
            }
        }
        if (genericType instanceof Class<?> clazz) return clazz;
        throw new RuntimeException("Unsupported join type: " + genericType);
    }

    private void validateJoinClass(Class<?> joinClass, String relationName) {
        if (joinClass == Void.class || joinClass == Void.TYPE) {
            throw new RuntimeException("@DbJoin '" + relationName + "' on " + entityClass.getName()
                    + " cannot use void/Void. Use a mapped class/record type or remove @DbJoin.");
        }
    }
}
