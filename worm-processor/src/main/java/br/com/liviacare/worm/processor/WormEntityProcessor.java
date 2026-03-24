package br.com.liviacare.worm.processor;

import br.com.liviacare.worm.annotation.audit.*;
import br.com.liviacare.worm.annotation.mapping.*;
import br.com.liviacare.worm.util.AliasUtils;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Annotation processor scaffold that generates metadata factory stubs for entities
 * annotated with {@link DbTable}.
 *
 * <p>This first phase intentionally delegates metadata creation to
 * {@code EntityMetadata.of(...)}. The generated class shape is the stable contract
 * for later phases where metadata becomes fully static and reflection-free.
 */
@SupportedAnnotationTypes("br.com.liviacare.worm.annotation.mapping.DbTable")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class WormEntityProcessor extends AbstractProcessor {

    private static final String FACTORY_PACKAGE = "br.com.liviacare.worm.orm.registry";
    private static final String FACTORY_SUFFIX = "_WormMetadataFactory";
    private static final String BINDER_PACKAGE = "br.com.liviacare.worm.orm.mapping";
    private static final String BINDER_SUFFIX = "Binder";

    private Filer filer;
    private Elements elements;
    private Types types;
    private Messager messager;
    private final Set<String> generatedProviderClassNames = new LinkedHashSet<>();
    private final Set<String> generatedBinderClassNames = new LinkedHashSet<>();

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.filer = processingEnv.getFiler();
        this.elements = processingEnv.getElementUtils();
        this.types = processingEnv.getTypeUtils();
        this.messager = processingEnv.getMessager();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(DbTable.class)) {
            if (!(element instanceof TypeElement entityType)) {
                continue;
            }
            if (!entityType.getModifiers().contains(Modifier.PUBLIC) || !isGeneratedFactoryAccessible(entityType)) {
                messager.printMessage(
                        Diagnostic.Kind.NOTE,
                        "Skipping non-public/inaccessible @DbTable type: " + entityType.getQualifiedName(),
                        entityType
                );
                continue;
            }
            try {
                String generatedClassName = writeFactoryClass(entityType);
                generatedProviderClassNames.add(generatedClassName);
                String generatedBinderClassName = writeBinderClass(entityType);
                if (generatedBinderClassName != null) {
                    generatedBinderClassNames.add(generatedBinderClassName);
                }
            } catch (IOException e) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "Failed to generate metadata factory for " + entityType.getQualifiedName() + ": " + e.getMessage(),
                        entityType
                );
            }
        }

        if (roundEnv.processingOver() && !generatedProviderClassNames.isEmpty()) {
            try {
                writeServiceFile();
            } catch (IOException e) {
                messager.printMessage(Diagnostic.Kind.ERROR, "Failed to generate service file: " + e.getMessage());
            }
        }
        return false;
    }

    private String writeBinderClass(TypeElement entityType) throws IOException {
        List<PropertyDescriptorModel> properties = collectSupportedProperties(entityType);
        if (properties.isEmpty()) {
            return null;
        }
        PropertyDescriptorModel idProperty = null;
        for (PropertyDescriptorModel property : properties) {
            if (property.id) {
                idProperty = property;
                break;
            }
        }
        if (idProperty == null) {
            return null;
        }

        List<BoundPropertyModel> boundProperties = new ArrayList<>(properties.size());
        for (PropertyDescriptorModel property : properties) {
            String accessor = resolveAccessor(entityType, property.propertyName);
            if (accessor == null) {
                messager.printMessage(Diagnostic.Kind.NOTE,
                        "Skipping static binder generation (missing accessor for field '" + property.propertyName + "'): "
                                + entityType.getQualifiedName(), entityType);
                return null;
            }
            boundProperties.add(new BoundPropertyModel(property, accessor));
        }

        String entityQualifiedName = entityType.getQualifiedName().toString();
        String simpleName = simpleName(entityType) + BINDER_SUFFIX;
        String generatedQualifiedName = BINDER_PACKAGE + "." + simpleName;
        boolean baseEntity = isBaseEntity(entityType);

        JavaFileObject file = filer.createSourceFile(generatedQualifiedName, entityType);
        try (Writer writer = file.openWriter()) {
            writer.write("package " + BINDER_PACKAGE + ";\n\n");
            writer.write("import org.springframework.jdbc.core.simple.JdbcClient;\n\n");
            writer.write("public final class " + simpleName + " implements EntityBinder<" + entityQualifiedName + "> {\n\n");

            writer.write("    @Override\n");
            writer.write("    public void bindInsert(JdbcClient.StatementSpec spec, " + entityQualifiedName + " entity) {\n");
            if (baseEntity) {
                writer.write("        entity.created();\n");
            }
            for (BoundPropertyModel property : boundProperties) {
                writer.write("        spec = spec.param(" + bindingExpression(property) + ");\n");
            }
            writer.write("    }\n\n");

            writer.write("    @Override\n");
            writer.write("    public void bindUpdate(JdbcClient.StatementSpec spec, " + entityQualifiedName + " entity) {\n");
            if (baseEntity) {
                writer.write("        entity.updated();\n");
            }
            for (BoundPropertyModel property : boundProperties) {
                PropertyDescriptorModel p = property.property;
                if (p.id || p.createdAt || p.createdBy || p.version) {
                    continue;
                }
                writer.write("        spec = spec.param(" + bindingExpression(property) + ");\n");
            }
            BoundPropertyModel idBound = null;
            for (BoundPropertyModel property : boundProperties) {
                if (property.property.id) {
                    idBound = property;
                    break;
                }
            }
            if (idBound != null) {
                writer.write("        spec = spec.param(" + bindingExpression(idBound) + ");\n");
            }
            PropertyDescriptorModel versionProperty = null;
            for (PropertyDescriptorModel property : properties) {
                if (property.version) {
                    versionProperty = property;
                    break;
                }
            }
            if (versionProperty != null) {
                for (BoundPropertyModel property : boundProperties) {
                    if (property.property.version) {
                        writer.write("        spec = spec.param(" + bindingExpression(property) + ");\n");
                        break;
                    }
                }
            }
            writer.write("    }\n");
            writer.write("}\n");
        }
        return generatedQualifiedName;
    }

    private boolean isBaseEntity(TypeElement entityType) {
        TypeElement iBaseEntity = elements.getTypeElement("br.com.liviacare.worm.api.iBaseEntity");
        return iBaseEntity != null && types.isAssignable(entityType.asType(), iBaseEntity.asType());
    }

    private String resolveAccessor(TypeElement entityType, String propertyName) {
        String suffix = Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
        String getter = "get" + suffix;
        String boolGetter = "is" + suffix;
        for (Element member : elements.getAllMembers(entityType)) {
            if (member.getKind() != ElementKind.METHOD || !member.getModifiers().contains(Modifier.PUBLIC)) {
                continue;
            }
            TypeElement owner = (TypeElement) member.getEnclosingElement();
            if (owner.getQualifiedName().contentEquals("java.lang.Object")) {
                continue;
            }
            var executable = (javax.lang.model.element.ExecutableElement) member;
            if (!executable.getParameters().isEmpty()) {
                continue;
            }
            String methodName = executable.getSimpleName().toString();
            if (!methodName.equals(getter) && !methodName.equals(boolGetter) && !methodName.equals(propertyName)) {
                continue;
            }
            return methodName + "()";
        }
        return null;
    }

    private static String bindingExpression(BoundPropertyModel property) {
        PropertyDescriptorModel p = property.property;
        String access = "entity." + property.accessorCall;
        if (p.createdAt || p.updatedAt) {
            if ("java.time.LocalDateTime".equals(p.typeLiteral)) {
                return "java.time.LocalDateTime.now()";
            }
            return "java.time.Instant.now()";
        }
        if (p.active) {
            if (p.primitive) {
                return Boolean.toString(p.activeDefaultValue);
            }
            return access + " == null ? " + p.activeDefaultValue + " : " + access;
        }
        if (p.typeLiteral.endsWith("[]")) {
            return access;
        }
        return access;
    }

    private static boolean isGeneratedFactoryAccessible(TypeElement entityType) {
        Element current = entityType;
        while (current != null && current.getKind() != ElementKind.PACKAGE) {
            if (current instanceof TypeElement te && !te.getModifiers().contains(Modifier.PUBLIC)) {
                return false;
            }
            current = current.getEnclosingElement();
        }
        return true;
    }

    private String writeFactoryClass(TypeElement entityType) throws IOException {
        String entityQualifiedName = entityType.getQualifiedName().toString();
        String simpleName = simpleName(entityType) + FACTORY_SUFFIX;
        String generatedQualifiedName = FACTORY_PACKAGE + "." + simpleName;

        DbTable dbTable = entityType.getAnnotation(DbTable.class);
        String tableName = dbTable.value().isBlank()
                ? entityType.getSimpleName().toString().toLowerCase()
                : dbTable.value();
        String module = dbTable.module().isBlank() ? null : dbTable.module();
        boolean tracked = entityType.getAnnotation(Track.class) != null;

        List<PropertyDescriptorModel> properties = collectSupportedProperties(entityType);
        List<JoinDescriptorModel> joins = collectToOneJoinDescriptors(entityType);
        boolean supportsStaticPath = supportsStaticGeneration(entityType, properties, joins);
        EntityOptionsModel options = resolveEntityOptions(properties, module, tracked);

        JavaFileObject file = filer.createSourceFile(generatedQualifiedName, entityType);
        try (Writer writer = file.openWriter()) {
            writer.write("package " + FACTORY_PACKAGE + ";\n\n");
            writer.write("import br.com.liviacare.worm.orm.converter.ConverterRegistry;\n");
            writer.write("import br.com.liviacare.worm.orm.dialect.SqlDialect;\n");
            writer.write("import br.com.liviacare.worm.orm.registry.EntityMetadata;\n");
            writer.write("import br.com.liviacare.worm.orm.registry.GeneratedEntityMetadataFactory;\n\n");
            writer.write("/**\n");
            writer.write(" * Generated metadata factory scaffold for " + entityQualifiedName + ".\n");
            writer.write(" *\n");
            writer.write(" * <p>This class is generated at compile time to avoid repeated metadata assembly\n");
            writer.write(" * decisions on startup and to keep a stable AOT integration point.\n");
            writer.write(" */\n");
            writer.write("public final class " + simpleName + " implements GeneratedEntityMetadataFactory<" + entityQualifiedName + "> {\n\n");

            if (supportsStaticPath) {
                writer.write("    private static final GeneratedMetadataRuntimeSupport.PropertyDescriptor[] PROPERTIES = new GeneratedMetadataRuntimeSupport.PropertyDescriptor[] {\n");
                for (int i = 0; i < properties.size(); i++) {
                    PropertyDescriptorModel property = properties.get(i);
                    writer.write("            new GeneratedMetadataRuntimeSupport.PropertyDescriptor(");
                    writer.write("\"" + property.propertyName + "\", ");
                    writer.write("\"" + property.columnName + "\", ");
                    writer.write("\"" + property.label + "\", ");
                    writer.write(property.typeLiteral + ".class, ");
                    writer.write(property.typeLiteral + ".class, ");
                    writer.write(property.id ? "true" : "false");
                    writer.write(", " + property.createdBy);
                    writer.write(", " + property.createdAt);
                    writer.write(", " + property.updatedAt);
                    writer.write(", " + property.active);
                    writer.write(", " + property.deletedAt);
                    writer.write(", " + property.version);
                    writer.write(", " + property.activeDefaultValue);
                    writer.write(")");
                    writer.write(i < properties.size() - 1 ? ",\n" : "\n");
                }
                writer.write("    };\n\n");
                writer.write("    private static final GeneratedMetadataRuntimeSupport.EntityOptions OPTIONS = new GeneratedMetadataRuntimeSupport.EntityOptions(\n");
                writer.write(options.module == null ? "            null,\n" : "            \"" + options.module + "\",\n");
                writer.write("            " + options.tracked + ",\n");
                writer.write(options.createdByColumn == null ? "            null,\n" : "            \"" + options.createdByColumn + "\",\n");
                writer.write(options.createdAtColumn == null ? "            null,\n" : "            \"" + options.createdAtColumn + "\",\n");
                writer.write(options.updatedAtColumn == null ? "            null,\n" : "            \"" + options.updatedAtColumn + "\",\n");
                writer.write(options.activeColumn == null ? "            null,\n" : "            \"" + options.activeColumn + "\",\n");
                writer.write("            " + options.activeDefaultValue + ",\n");
                writer.write(options.deletedAtColumn == null ? "            null,\n" : "            \"" + options.deletedAtColumn + "\",\n");
                writer.write(options.versionColumn == null ? "            null\n" : "            \"" + options.versionColumn + "\"\n");
                writer.write("    );\n\n");
                writer.write("    private static final GeneratedMetadataRuntimeSupport.JoinDescriptor[] JOINS = new GeneratedMetadataRuntimeSupport.JoinDescriptor[] {\n");
                for (int i = 0; i < joins.size(); i++) {
                    JoinDescriptorModel join = joins.get(i);
                    writer.write("            new GeneratedMetadataRuntimeSupport.JoinDescriptor(");
                    writer.write("\"" + join.fieldName + "\", ");
                    writer.write(join.joinTypeLiteral + ".class, ");
                    writer.write("\"" + join.table + "\", ");
                    writer.write("\"" + join.alias + "\", ");
                    writer.write("\"" + join.on + "\", ");
                    writer.write("br.com.liviacare.worm.annotation.mapping.DbJoin.Type." + join.joinType);
                    writer.write(")");
                    writer.write(i < joins.size() - 1 ? ",\n" : "\n");
                }
                writer.write("    };\n\n");
            }

            writer.write("    @Override\n");
            writer.write("    public Class<" + entityQualifiedName + "> entityClass() {\n");
            writer.write("        return " + entityQualifiedName + ".class;\n");
            writer.write("    }\n\n");
            writer.write("    @Override\n");
            writer.write("    public EntityMetadata<" + entityQualifiedName + "> create(SqlDialect dialect, ConverterRegistry converterRegistry) {\n");
            if (supportsStaticPath) {
                writer.write("        return GeneratedMetadataRuntimeSupport.buildEntityMetadata(\n");
                writer.write("                " + entityQualifiedName + ".class,\n");
                writer.write("                \"" + tableName + "\",\n");
                writer.write("                PROPERTIES,\n");
                writer.write("                JOINS,\n");
                writer.write("                dialect,\n");
                writer.write("                converterRegistry,\n");
                writer.write("                OPTIONS\n");
                writer.write("        );\n");
            } else {
                writer.write("        return EntityMetadata.of(" + entityQualifiedName + ".class, dialect, converterRegistry);\n");
            }
            writer.write("    }\n");
            writer.write("}\n");
        }
        return generatedQualifiedName;
    }

    private List<PropertyDescriptorModel> collectSupportedProperties(TypeElement entityType) {
        List<PropertyDescriptorModel> out = new ArrayList<>();
        for (Element enclosed : entityType.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) {
                continue;
            }
            VariableElement field = (VariableElement) enclosed;
            // Skip static fields, join mappings and transient fields (not persisted)
            if (field.getModifiers().contains(Modifier.STATIC)
                    || field.getAnnotation(DbJoin.class) != null
                    || field.getModifiers().contains(Modifier.TRANSIENT)) {
                continue;
            }
            DbId dbId = field.getAnnotation(DbId.class);
            DbColumn dbColumn = field.getAnnotation(DbColumn.class);
            String property = field.getSimpleName().toString();
            String column = resolveColumnName(field, dbId, dbColumn);
            String label = column;
            String typeLiteral = classLiteralType(field.asType());
            boolean primitive = field.asType().getKind().isPrimitive();
            Active active = field.getAnnotation(Active.class);

            TypeElement activeRecordType = elements.getTypeElement("br.com.liviacare.worm.ActiveRecord");
            boolean isActiveRecord = activeRecordType != null && types.isAssignable(entityType.asType(), activeRecordType.asType());
            if (isActiveRecord && isJsonCandidate(field.asType()) && (dbColumn == null || !dbColumn.json()) && dbId == null) {
                continue;
            }

            out.add(new PropertyDescriptorModel(
                    property,
                    column,
                    label,
                    typeLiteral,
                    primitive,
                    dbId != null,
                    field.getAnnotation(CreatedBy.class) != null,
                    field.getAnnotation(CreatedAt.class) != null,
                    field.getAnnotation(UpdatedAt.class) != null,
                    active != null,
                    field.getAnnotation(DeletedAt.class) != null,
                    field.getAnnotation(DbVersion.class) != null,
                    active == null || active.defaultValue()
            ));
        }
        return out;
    }

    /**
     * Heuristic: decide whether a TypeMirror represents a non-scalar / JSON-candidate type.
     * We treat java.* and javax.* scalar types (String, boxed primitives, java.time.*, UUID)
     * as non-JSON candidates; collections/maps and any user-defined types are JSON candidates.
     */
    private boolean isJsonCandidate(TypeMirror tm) {
        if (tm == null) return false;
        if (tm.getKind().isPrimitive()) return false;
        String s = tm.toString();
        if ("java.lang.Object".equals(s)) return true;
        // Collections and maps are explicit JSON candidates
        if (s.startsWith("java.util.List") || s.startsWith("java.util.Collection") || s.startsWith("java.util.Map")) return true;
        // Treat common scalar java types as non-json-candidate
        if (s.equals("java.lang.String") || s.equals("java.lang.Boolean") || s.equals("java.lang.Byte")
                || s.equals("java.lang.Short") || s.equals("java.lang.Integer") || s.equals("java.lang.Long")
                || s.equals("java.lang.Float") || s.equals("java.lang.Double") || s.startsWith("java.time.")
                || s.equals("java.util.UUID")) return false;
        // Anything outside java./javax. packages is likely an application object => json candidate
        if (!s.startsWith("java.") && !s.startsWith("javax.")) return true;
        return false;
    }

    private boolean supportsStaticGeneration(TypeElement entityType, List<PropertyDescriptorModel> properties, List<JoinDescriptorModel> joins) {
        if (entityType.getKind() != ElementKind.CLASS) {
            return false;
        }
        boolean hasId = false;
        for (PropertyDescriptorModel property : properties) {
            if (property.id) {
                hasId = true;
                break;
            }
        }
        if (!hasId) {
            return false;
        }
        for (Element enclosed : entityType.getEnclosedElements()) {
            // Ignore non-field members and transient fields for generation checks
            if (enclosed.getKind() != ElementKind.FIELD) continue;
            VariableElement field = (VariableElement) enclosed;
            if (field.getModifiers().contains(Modifier.TRANSIENT)) continue;

            DbJoin join = field.getAnnotation(DbJoin.class);
            if (join != null) {
                if (isCollectionField(field)) {
                    return false;
                }
            }
            if (field.getAnnotation(UpdatedBy.class) != null) {
                return false;
            }
            if (field.getAnnotation(DbColumn.class) != null) {
                DbColumn dbColumn = field.getAnnotation(DbColumn.class);
                if (dbColumn.json() || !dbColumn.expr().isBlank()) {
                    return false;
                }
            }
        }
        return joins.size() == countToOneJoins(entityType);
    }

    private int countToOneJoins(TypeElement entityType) {
        int count = 0;
        for (Element enclosed : entityType.getEnclosedElements()) {
            if (enclosed.getAnnotation(DbJoin.class) != null) {
                count++;
            }
        }
        return count;
    }

    private List<JoinDescriptorModel> collectToOneJoinDescriptors(TypeElement entityType) {
        List<JoinDescriptorModel> joins = new ArrayList<>();
        String mainAlias = AliasUtils.defaultMainAlias(entityType.getSimpleName().toString());
        for (Element enclosed : entityType.getEnclosedElements()) {
            if (!(enclosed instanceof VariableElement field)) {
                continue;
            }
            DbJoin join = field.getAnnotation(DbJoin.class);
            if (join == null || isCollectionField(field)) {
                continue;
            }
            TypeElement joinType = asTypeElement(field.asType());
            if (joinType == null) {
                continue;
            }
            String relationName = field.getSimpleName().toString();
            String table = resolveJoinTable(join, joinType);
            String alias = join.alias().isBlank() ? AliasUtils.defaultJoinAlias(relationName, table) : AliasUtils.sanitizeAlias(join.alias());
            String on = resolveJoinOn(entityType, field, join, joinType, alias, relationName, mainAlias);
            joins.add(new JoinDescriptorModel(relationName, joinType.getQualifiedName().toString(), table, alias, on, join.type().name()));
        }
        return joins;
    }

    private static boolean isCollectionField(VariableElement field) {
        return field.asType().toString().startsWith("java.util.List<")
                || field.asType().toString().startsWith("java.util.Collection<");
    }

    private static TypeElement asTypeElement(TypeMirror typeMirror) {
        if (typeMirror instanceof DeclaredType declaredType && declaredType.asElement() instanceof TypeElement typeElement) {
            return typeElement;
        }
        return null;
    }

    private static String resolveJoinTable(DbJoin ann, TypeElement joinType) {
        if (!ann.table().isBlank()) return ann.table();
        if (!ann.value().isBlank()) return ann.value();
        DbTable dbTable = joinType.getAnnotation(DbTable.class);
        if (dbTable != null && !dbTable.value().isBlank()) return dbTable.value();
        throw new IllegalStateException("@DbJoin requires table/value or joined type annotated with @DbTable: " + joinType.getQualifiedName());
    }

    private String resolveJoinOn(TypeElement entityType,
                                 VariableElement joinField,
                                 DbJoin ann,
                                 TypeElement joinType,
                                 String alias,
                                 String relationName,
                                 String mainAlias) {
        if (!ann.on().isBlank()) {
            return ann.on();
        }
        String referencedColumn = !ann.targetColumn().isBlank() ? ann.targetColumn()
                : (ann.referencedColumn().isBlank() ? "id" : ann.referencedColumn());
        if (!ann.localColumn().isBlank()) {
            validateMainEntityColumn(entityType, ann.localColumn(), relationName, "localColumn");
            validateJoinColumn(joinType, referencedColumn, relationName, "referencedColumn/targetColumn");
            return alias + "." + referencedColumn + " = " + mainAlias + "." + ann.localColumn();
        }
        validateJoinColumn(joinType, referencedColumn, relationName, "referencedColumn/targetColumn");
        String inferredLocal = toSnakeCase(relationName) + "_id";
        return alias + "." + referencedColumn + " = " + mainAlias + "." + inferredLocal;
    }

    private void validateMainEntityColumn(TypeElement entityType, String column, String relationName, String attribute) {
        for (Element enclosed : entityType.getEnclosedElements()) {
            if (!(enclosed instanceof VariableElement field)) continue;
            if (field.getAnnotation(DbJoin.class) != null) continue;
            String resolved = resolveColumnName(field, field.getAnnotation(DbId.class), field.getAnnotation(DbColumn.class));
            if (column.equals(resolved)) {
                return;
            }
        }
        throw new IllegalStateException("@DbJoin(" + attribute + ") on relation '" + relationName + "' references unknown main column '" + column + "'");
    }

    private void validateJoinColumn(TypeElement joinType, String column, String relationName, String attribute) {
        for (Element enclosed : joinType.getEnclosedElements()) {
            if (!(enclosed instanceof VariableElement field)) continue;
            if (field.getModifiers().contains(Modifier.STATIC)) continue;
            String resolved = resolveColumnName(field, field.getAnnotation(DbId.class), field.getAnnotation(DbColumn.class));
            if (column.equals(resolved)) {
                return;
            }
        }
        throw new IllegalStateException("@DbJoin(" + attribute + ") on relation '" + relationName + "' references unknown join column '" + column + "' from " + joinType.getQualifiedName());
    }

    private static String toSnakeCase(String value) {
        if (value == null || value.isBlank()) return "id";
        StringBuilder out = new StringBuilder(value.length() + 4);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isUpperCase(c) && i > 0) out.append('_');
            out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }

    private static String resolveColumnName(VariableElement field, DbId dbId, DbColumn dbColumn) {
        if (dbColumn != null && !dbColumn.value().isBlank()) {
            return dbColumn.value();
        }
        if (dbId != null && !dbId.value().isBlank()) {
            return dbId.value();
        }
        CreatedAt createdAt = field.getAnnotation(CreatedAt.class);
        if (createdAt != null) return createdAt.value();
        UpdatedAt updatedAt = field.getAnnotation(UpdatedAt.class);
        if (updatedAt != null) return updatedAt.value();
        DeletedAt deletedAt = field.getAnnotation(DeletedAt.class);
        if (deletedAt != null) return deletedAt.value();
        Active active = field.getAnnotation(Active.class);
        if (active != null) return active.value();
        if (field.getAnnotation(CreatedBy.class) != null) return "created_by";
        return field.getSimpleName().toString();
    }

    private static EntityOptionsModel resolveEntityOptions(List<PropertyDescriptorModel> properties, String module, boolean tracked) {
        String createdByColumn = null;
        String createdAtColumn = null;
        String updatedAtColumn = null;
        String activeColumn = null;
        boolean activeDefaultValue = true;
        String deletedAtColumn = null;
        String versionColumn = null;

        for (PropertyDescriptorModel property : properties) {
            if (property.createdBy && createdByColumn == null) createdByColumn = property.columnName;
            if (property.createdAt && createdAtColumn == null) createdAtColumn = property.columnName;
            if (property.updatedAt && updatedAtColumn == null) updatedAtColumn = property.columnName;
            if (property.active && activeColumn == null) {
                activeColumn = property.columnName;
                activeDefaultValue = property.activeDefaultValue;
            }
            if (property.deletedAt && deletedAtColumn == null) deletedAtColumn = property.columnName;
            if (property.version && versionColumn == null) versionColumn = property.columnName;
        }

        return new EntityOptionsModel(module, tracked, createdByColumn, createdAtColumn, updatedAtColumn,
                activeColumn, activeDefaultValue, deletedAtColumn, versionColumn);
    }

    private String classLiteralType(TypeMirror type) {
        if (type.getKind().isPrimitive()) {
            return type.toString();
        }
        return types.erasure(type).toString();
    }

    private record BoundPropertyModel(
            PropertyDescriptorModel property,
            String accessorCall
    ) {
    }

    private record PropertyDescriptorModel(
            String propertyName,
            String columnName,
            String label,
            String typeLiteral,
            boolean primitive,
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

    private record EntityOptionsModel(
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
    }

    private record JoinDescriptorModel(
            String fieldName,
            String joinTypeLiteral,
            String table,
            String alias,
            String on,
            String joinType
    ) {
    }

    private void writeServiceFile() throws IOException {
        writeServices("br.com.liviacare.worm.orm.registry.GeneratedEntityMetadataFactory", generatedProviderClassNames);
        if (!generatedBinderClassNames.isEmpty()) {
            writeServices("br.com.liviacare.worm.orm.mapping.EntityBinder", generatedBinderClassNames);
        }
    }

    private void writeServices(String serviceType, Set<String> implementations) throws IOException {
        String servicePath = "META-INF/services/" + serviceType;
        FileObject file = filer.createResource(StandardLocation.CLASS_OUTPUT, "", servicePath);
        try (Writer writer = file.openWriter()) {
            for (String provider : implementations) {
                writer.write(provider);
                writer.write('\n');
            }
        }
    }

    private static String simpleName(TypeElement typeElement) {
        Element parent = typeElement.getEnclosingElement();
        while (!(parent instanceof PackageElement) && parent != null) {
            parent = parent.getEnclosingElement();
        }
        String packageName = parent instanceof PackageElement p ? p.getQualifiedName().toString() : "";
        String qualified = typeElement.getQualifiedName().toString();
        if (packageName.isEmpty()) {
            return qualified.replace('.', '_');
        }
        String local = qualified.substring(packageName.length() + 1);
        return local.replace('.', '_');
    }
}

