package br.com.liviacare.worm.orm.registry;

import br.com.liviacare.worm.annotation.mapping.DbColumn;
import br.com.liviacare.worm.orm.mapping.ColumnConverter;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Metadata for a projection (Java Record) built from an entity's metadata.
 */
public final class ProjectionMetadata {
    private final Class<?> projectionClass;
    final String[] selectedLabels; // column labels to read from ResultSet
    final String selectSql; // full SELECT ... FROM ... SQL fragment (without WHERE)
    final MethodHandle constructor;
    final Class<?>[] componentTypes;
    final ColumnConverter[] converters;

    private ProjectionMetadata(Class<?> projectionClass, String[] selectedLabels, String selectSql,
                               MethodHandle constructor, Class<?>[] componentTypes, ColumnConverter[] converters) {
        this.projectionClass = projectionClass;
        this.selectedLabels = selectedLabels;
        this.selectSql = selectSql;
        this.constructor = constructor;
        this.componentTypes = componentTypes;
        this.converters = converters;
    }

    public Class<?> projectionClass() { return projectionClass; }
    public String[] selectedLabels() { return selectedLabels; }
    public String selectSql() { return selectSql; }
    public MethodHandle constructor() { return constructor; }
    public Class<?>[] componentTypes() { return componentTypes; }
    public ColumnConverter[] converters() { return converters; }

    static ProjectionMetadata of(Class<?> projection, EntityMetadata<?> source, br.com.liviacare.worm.orm.converter.ConverterRegistry registry) {
        Objects.requireNonNull(projection, "projection class required");
        if (!projection.isRecord()) throw new IllegalArgumentException("Only Java records are supported as projections: " + projection.getName());
        try {
            final MethodHandles.Lookup lookup = MethodHandles.lookup();
            final MethodHandles.Lookup pl = MethodHandles.privateLookupIn(projection, lookup);

            final RecordComponent[] comps = projection.getRecordComponents();
            final List<String> selectExprs = new ArrayList<>();
            final List<String> labels = new ArrayList<>();
            final List<Class<?>> types = new ArrayList<>();
            final List<ColumnConverter> convs = new ArrayList<>();

            for (RecordComponent rc : comps) {
                String col = rc.isAnnotationPresent(DbColumn.class)
                        ? rc.getAnnotation(DbColumn.class).value()
                        : rc.getName();
                // check main table columns
                if (source.selectColumns().contains(col)) {
                    selectExprs.add(source.tableName() + "." + col + " AS " + col);
                    labels.add(col);
                } else {
                    // try joins
                    boolean found = false;
                    for (JoinInfo ji : source.joinInfos()) {
                        if (ji == null) continue;
                        if (ji.getJoinColumnNames().contains(col)) {
                            String label = ji.getAlias() + "_" + col;
                            selectExprs.add(ji.getAlias() + "." + col + " AS " + label);
                            labels.add(label);
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        throw new IllegalArgumentException("Projection component '" + rc.getName() + "' cannot be mapped to entity '" + source.entityClass().getName() + "' columns");
                    }
                }
                types.add(rc.getType());
                // converter: prefer registry
                if (registry != null && registry.hasConverter(rc.getType())) {
                    convs.add(raw -> registry.fromDatabase(raw, rc.getType()));
                } else {
                    // simple default converter handling enums
                    if (rc.getType().isEnum()) {
                        convs.add(raw -> {
                            if (raw == null) return null;
                            try {
                                return Enum.valueOf((Class) rc.getType(), raw.toString());
                            } catch (Exception e) {
                                return null;
                            }
                        });
                    } else {
                        convs.add(raw -> raw);
                    }
                }
            }

            final Class<?>[] compTypes = types.toArray(new Class<?>[0]);
            final ColumnConverter[] convArr = convs.toArray(new ColumnConverter[0]);
            final String selectSql = "SELECT " + String.join(", ", selectExprs) + " FROM " + source.tableName() + buildProjectionJoins(source, labels);

            final MethodHandle ctor = pl.findConstructor(projection, MethodType.methodType(void.class, compTypes));
            return new ProjectionMetadata(projection, labels.toArray(new String[0]), selectSql, ctor, compTypes, convArr);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to create ProjectionMetadata for " + projection.getName(), e);
        }
    }

    private static String buildProjectionJoins(EntityMetadata<?> source, List<String> labels) {
        // include only joins that supply columns in labels
        StringBuilder sb = new StringBuilder();
        for (JoinInfo ji : source.joinInfos()) {
            if (ji == null) continue;
            boolean needed = false;
            for (String colLabel : labels) {
                if (colLabel.startsWith(ji.getAlias() + "_")) { needed = true; break; }
            }
            if (needed) {
                sb.append(' ').append(ji.type.name()).append(" JOIN ")
                  .append(ji.table).append(' ').append(ji.alias).append(" ON ").append(ji.on);
            }
        }
        return sb.toString();
    }
}
