package br.com.liviacare.worm.orm.registry;

import br.com.liviacare.worm.orm.mapping.ColumnConverter;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Type;
import java.util.*;

/**
 * Metadata about an entity type. Built once at startup and entirely
 * composed of pre-resolved MethodHandles and immutable structures.
 *
 * <p>Supports both Java records (canonical constructor) and regular classes (no-arg constructor + setters).
 */
public final class EntityMetadata<T> {

    // ── Core identity ────────────────────────────────────────────────────────
    private final Class<T> entityClass;
    private final boolean isRecord;
    private final String tableName;
    private final String idColumnName;
    private final MethodHandle idGetter;

    // ── Parameter metadata (one entry per mapped member, including joins) ────
    private final int paramCount;
    private final String[]          paramColumnLabels; // null for join params
    private final Class<?>[]        paramTypes;
    private final Type[]            paramGenericTypes;
    private final ColumnConverter[] paramConverters;
    private final MethodHandle[]    paramSetters;      // null for records / join record params
    private final JoinInfo[]        joinInfos;         // null for non-join params

    // ── Select-column metadata (non-join columns only, aligned) ─────────────
    private final List<String>   selectColumns;
    private final List<String>   selectLabels;
    private final Class<?>[]     selectTypes;
    private final MethodHandle[] selectGetters;
    private final MethodHandle[] classSetters;         // null entries for records
    private final Set<String>    jsonColumns;

    // ── Mutation metadata ────────────────────────────────────────────────────
    private final List<String> insertableColumns;
    private final List<String> updatableColumns;
    private final MethodHandle constructor;

    // ── Pre-built SQL strings ────────────────────────────────────────────────
    private final String selectSql;
    private final String countSql;
    private final String insertSql;
    private final String updateSql;
    private final String upsertSql;
    private final String deleteSql;
    private final String softDeleteSql;

    // ── Soft-delete / auditing columns ──────────────────────────────────────
    private final boolean          hasActive;
    private final String           activeColumn;
    private final boolean          hasDeletedAt;
    private final String           deletedAtColumn;
    private final Optional<String> createdByColumn;
    private final Optional<String> createdAtColumn;
    private final Optional<String> updatedAtColumn;

    // ── Optimistic locking ───────────────────────────────────────────────────
    private final boolean      hasVersion;
    private final String       versionColumn;
    private final MethodHandle versionGetter;
    private final MethodHandle versionSetter;

    // ── Ordering ─────────────────────────────────────────────────────────────
    private final String defaultOrderBy;

    // ── Module routing ───────────────────────────────────────────────────────
    /** Logical module name from {@code @DbTable(module = "...")}, used to select DataSource. May be null. */
    private final String module;

    // ── Column index (label → position in selectColumns) ────────────────────
    private final Map<String, Integer> columnIndex;

    // =========================================================================
    // Private constructor — use Builder
    // =========================================================================
    EntityMetadata(Builder<T> b) {
        this.entityClass        = b.entityClass;
        this.isRecord           = b.isRecord;
        this.tableName          = b.tableName;
        this.idColumnName       = b.idColumnName;
        this.idGetter           = b.idGetter;
        this.paramCount         = b.paramColumnLabels.length;
        this.paramColumnLabels  = b.paramColumnLabels;
        this.paramTypes         = b.paramTypes;
        this.paramGenericTypes  = b.paramGenericTypes;
        this.paramConverters    = b.paramConverters;
        this.paramSetters       = b.paramSetters;
        this.joinInfos          = b.joinInfos;
        this.selectColumns      = List.copyOf(b.selectColumns);
        this.selectLabels       = List.copyOf(b.selectLabels);
        this.selectTypes        = b.selectTypes;
        this.selectGetters      = b.selectGetters;
        this.classSetters       = b.classSetters;
        this.jsonColumns        = Set.copyOf(b.jsonColumns);
        this.insertableColumns  = List.copyOf(b.insertableColumns);
        this.updatableColumns   = List.copyOf(b.updatableColumns);
        this.constructor        = b.constructor;
        this.selectSql          = b.selectSql;
        this.countSql           = b.countSql;
        this.insertSql          = b.insertSql;
        this.updateSql          = b.updateSql;
        this.upsertSql          = b.upsertSql;
        this.deleteSql          = b.deleteSql;
        this.softDeleteSql      = b.softDeleteSql;
        this.hasActive          = b.hasActive;
        this.activeColumn       = b.activeColumn;
        this.hasDeletedAt       = b.hasDeletedAt;
        this.deletedAtColumn    = b.deletedAtColumn;
        this.createdByColumn    = b.createdByColumn;
        this.createdAtColumn    = b.createdAtColumn;
        this.updatedAtColumn    = b.updatedAtColumn;
        this.hasVersion         = b.hasVersion;
        this.versionColumn      = b.versionColumn;
        this.versionGetter      = b.versionGetter;
        this.versionSetter      = b.versionSetter;
        this.defaultOrderBy     = b.defaultOrderBy;
        this.module             = b.module;
        this.columnIndex        = Map.copyOf(b.columnIndex);
    }

    // =========================================================================
    // Factory
    // =========================================================================

    public static <T> EntityMetadata<T> of(Class<T> entityClass) {
        return of(entityClass, null, null);
    }

    public static <T> EntityMetadata<T> of(
            Class<T> entityClass,
            br.com.liviacare.worm.orm.dialect.SqlDialect dialect,
            br.com.liviacare.worm.orm.converter.ConverterRegistry converterRegistry) {
        try {
            return new MetadataBuilder<>(entityClass, dialect, converterRegistry).build();
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("Failed to create EntityMetadata for " + entityClass.getName(), e);
        }
    }

    // =========================================================================
    // Public accessors
    // =========================================================================

    public Class<T>          entityClass()        { return entityClass; }
    public boolean           isRecord()            { return isRecord; }
    public String            tableName()           { return tableName; }
    public String            idColumnName()        { return idColumnName; }
    public MethodHandle      idGetter()            { return idGetter; }
    public int               paramCount()          { return paramCount; }
    public String[]          paramColumnLabels()   { return paramColumnLabels; }
    public Class<?>[]        paramTypes()          { return paramTypes; }
    public Type[]            paramGenericTypes()   { return paramGenericTypes; }
    public ColumnConverter[] paramConverters()     { return paramConverters; }
    public MethodHandle[]    paramSetters()        { return paramSetters; }
    public JoinInfo[]        joinInfos()           { return joinInfos; }
    public List<String>      selectColumns()       { return selectColumns; }
    public List<String>      selectLabels()        { return selectLabels; }
    public Class<?>[]        selectTypes()         { return selectTypes; }
    public MethodHandle[]    selectGetters()       { return selectGetters; }
    public MethodHandle[]    classSetters()        { return classSetters; }
    public boolean           isJsonColumn(String col) { return jsonColumns.contains(col); }
    public List<String>      insertableColumns()   { return insertableColumns; }
    public List<String>      updatableColumns()    { return updatableColumns; }
    public MethodHandle      constructor()         { return constructor; }
    public String            selectSql()           { return selectSql; }
    public String            countSql()            { return countSql; }
    public String            insertSql()           { return insertSql; }
    public String            updateSql()           { return updateSql; }
    public String            upsertSql()           { return upsertSql; }
    public String            deleteSql()           { return deleteSql; }
    public String            softDeleteSql()       { return softDeleteSql; }
    public boolean           hasActive()           { return hasActive; }
    public String            activeColumn()        { return activeColumn; }
    public boolean           hasDeletedAt()        { return hasDeletedAt; }
    public String            deletedAtColumn()     { return deletedAtColumn; }
    public Optional<String>  createdByColumn()     { return createdByColumn; }
    public Optional<String>  createdAtColumn()     { return createdAtColumn; }
    public Optional<String>  updatedAtColumn()     { return updatedAtColumn; }
    public boolean           hasVersion()          { return hasVersion; }
    public String            versionColumn()       { return versionColumn; }
    public MethodHandle      versionGetter()       { return versionGetter; }
    public MethodHandle      versionSetter()       { return versionSetter; }
    public String            defaultOrderBy()      { return defaultOrderBy; }
    /** Logical module name declared in {@code @DbTable(module = "...")}, or null if not set. */
    public String            module()              { return module; }

    public int columnIndex(String column) {
        return columnIndex.getOrDefault(column, -1);
    }

    /** Returns true if any join field is a List/Collection (one-to-many join). */
    public boolean hasCollectionJoins() {
        if (joinInfos == null) return false;
        for (JoinInfo ji : joinInfos) {
            if (ji != null && ji.isList()) return true;
        }
        return false;
    }

    /**
     * Maps a Java property name to its DB column label using exact, snake_case, and case-insensitive matching.
     */
    public String columnForProperty(String property) {
        if (property == null || property.isBlank()) return null;
        String snake = property.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
        for (int i = 0; i < selectLabels.size(); i++) {
            String label = selectLabels.get(i);
            String col   = selectColumns.get(i);
            if (property.equals(label) || snake.equals(label) || snake.equals(col) || property.equalsIgnoreCase(label))
                return col;
        }
        return null;
    }

    // =========================================================================
    // Builder (internal data holder)
    // =========================================================================

    static final class Builder<T> {
        Class<T> entityClass;
        boolean isRecord;
        String tableName, idColumnName, selectSql, countSql, insertSql,
                updateSql, upsertSql, deleteSql, softDeleteSql;
        String activeColumn, deletedAtColumn, versionColumn, defaultOrderBy;
        String module; // from @DbTable(module = "...")
        boolean hasActive, hasDeletedAt, hasVersion;
        MethodHandle idGetter, constructor, versionGetter, versionSetter;
        Optional<String> createdByColumn, createdAtColumn, updatedAtColumn;
        String[]          paramColumnLabels;
        Class<?>[]        paramTypes, selectTypes;
        Type[]            paramGenericTypes;
        ColumnConverter[] paramConverters;
        MethodHandle[]    paramSetters, selectGetters, classSetters;
        JoinInfo[]        joinInfos;
        List<String>      selectColumns, selectLabels, insertableColumns, updatableColumns;
        Set<String>       jsonColumns = new HashSet<>();
        Map<String, Integer> columnIndex;
    }
}
