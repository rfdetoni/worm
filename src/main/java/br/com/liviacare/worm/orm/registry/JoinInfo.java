package br.com.liviacare.worm.orm.registry;

import br.com.liviacare.worm.annotation.mapping.DbJoin;
import br.com.liviacare.worm.orm.mapping.ColumnConverter;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Metadata for a joined entity.
 */
public final class JoinInfo {
    // Set after initial construction
    String table;
    String alias;
    String on;
    DbJoin.Type type;

    // Set during inspection
    final Class<?>          joinClass;
    final boolean           isRecord;
    final MethodHandle      joinConstructor;
    final MethodHandle[]    joinSetters;   // class only
    final MethodHandle[]    joinAccessors; // record only
    final List<String>      joinColumnNames;
    final List<Class<?>>    joinColumnTypes;
    final List<String>      resultLabels = new ArrayList<>();
    final ColumnConverter[] joinConverters;

    /** True when the entity field/component that holds this join is a List or Collection type. */
    boolean isList;

    /** MethodHandle for reading the join field from the main entity instance. Required for collection merging in classes. */
    MethodHandle fieldGetter;

    /** Raw Field reference for direct reflection access on POJO entities — more reliable than MethodHandle for generic List fields. */
    java.lang.reflect.Field joinField;

    JoinInfo(Class<?> joinClass, boolean isRecord, MethodHandle joinConstructor,
             MethodHandle[] joinSetters, MethodHandle[] joinAccessors,
             List<String> joinColumnNames, List<Class<?>> joinColumnTypes,
             ColumnConverter[] joinConverters) {
        this.joinClass       = joinClass;
        this.isRecord        = isRecord;
        this.joinConstructor = joinConstructor;
        this.joinSetters     = joinSetters;
        this.joinAccessors   = joinAccessors;
        this.joinColumnNames = joinColumnNames;
        this.joinColumnTypes = joinColumnTypes;
        this.joinConverters  = joinConverters;
    }

    public String           getTable()           { return table; }
    public String           getAlias()           { return alias; }
    public String           getOn()              { return on; }
    public DbJoin.Type      getType()            { return type; }
    public Class<?>         getJoinClass()       { return joinClass; }
    public boolean          isRecord()           { return isRecord; }
    public MethodHandle     getJoinConstructor() { return joinConstructor; }
    public MethodHandle[]   getJoinSetters()     { return joinSetters; }
    public MethodHandle[]   getJoinAccessors()   { return joinAccessors; }
    public List<String>     getJoinColumnNames() { return Collections.unmodifiableList(joinColumnNames); }
    public List<Class<?>>   getJoinColumnTypes() { return Collections.unmodifiableList(joinColumnTypes); }
    public List<String>     getResultLabels()    { return Collections.unmodifiableList(resultLabels); }
    public ColumnConverter[] getJoinConverters() { return joinConverters; }
    /** Returns true if the entity field holding this join is a List or Collection. */
    public boolean          isList()             { return isList; }

    /** Returns the MethodHandle to read the join field from the main entity instance. */
    public MethodHandle     getFieldGetter()      { return fieldGetter; }

    /** Returns the raw Field for direct reflection access on POJO join fields. */
    public java.lang.reflect.Field getJoinField() { return joinField; }
}
