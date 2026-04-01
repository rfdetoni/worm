package br.com.liviacare.worm.processor;

import br.com.liviacare.worm.annotation.audit.Active;
import br.com.liviacare.worm.annotation.audit.CreatedAt;
import br.com.liviacare.worm.annotation.audit.CreatedBy;
import br.com.liviacare.worm.annotation.audit.DeletedAt;
import br.com.liviacare.worm.annotation.audit.UpdatedAt;
import br.com.liviacare.worm.annotation.mapping.DbColumn;
import br.com.liviacare.worm.annotation.mapping.DbId;
import br.com.liviacare.worm.annotation.mapping.DbJoin;
import br.com.liviacare.worm.annotation.mapping.DbTable;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.io.Writer;
import java.util.Map;
import java.util.Set;

/**
 * Annotation processor that generates {@code {EntityName}_.java} static metamodel classes.
 *
 * <p>For each {@code @DbTable} entity, a companion class is generated in the same package
 * with the suffix {@code _}, containing:
 * <ul>
 *   <li>{@code public static final String COLUMN_<UPPER_SNAKE>} — raw column name constant.</li>
 *   <li>{@code public static final WormAttribute<EntityType, FieldType> <fieldName>} — typed
 *       column descriptor usable in the type-safe {@link br.com.liviacare.worm.query.FilterBuilder}
 *       overloads.</li>
 * </ul>
 *
 * <p>Example generated class:
 * <pre>{@code
 * // Generated — do not edit
 * public final class User_ {
 *     public static final String COLUMN_FIRST_NAME = "first_name";
 *     public static final WormAttribute<User, String> firstName =
 *         new WormAttribute<>("first_name", String.class);
 *     // ...
 * }
 * }</pre>
 */
@SupportedAnnotationTypes("br.com.liviacare.worm.annotation.mapping.DbTable")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class WormMetamodelProcessor extends AbstractProcessor {

    private static final String WORM_ATTRIBUTE_FQN = "br.com.liviacare.worm.orm.registry.WormAttribute";
    private static final String METAMODEL_SUFFIX = "_";
    private static final String QUERY_PATH_PREFIX = "W";

    private Filer filer;
    private Messager messager;
    private Elements elements;
    private Types types;
    private TypeMirror temporalType;
    private TypeMirror comparableType;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.filer = processingEnv.getFiler();
        this.messager = processingEnv.getMessager();
        this.elements = processingEnv.getElementUtils();
        this.types = processingEnv.getTypeUtils();
        TypeElement temporal = elements.getTypeElement("java.time.temporal.Temporal");
        TypeElement comparable = elements.getTypeElement("java.lang.Comparable");
        this.temporalType = temporal != null ? temporal.asType() : null;
        this.comparableType = comparable != null ? comparable.asType() : null;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(DbTable.class)) {
            if (!(element instanceof TypeElement entityType)) continue;
            if (!entityType.getModifiers().contains(Modifier.PUBLIC) || !isGeneratedTypeAccessible(entityType)) continue;
            try {
                writeMetamodelClass(entityType);
                writeQueryPathClass(entityType);
            } catch (IOException e) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "WormMetamodelProcessor: failed to generate metamodel for "
                                + entityType.getQualifiedName() + ": " + e.getMessage(),
                        entityType);
            }
        }
        return false; // don't claim the annotation — let WormEntityProcessor run too
    }

    private static boolean isGeneratedTypeAccessible(TypeElement entityType) {
        Element current = entityType;
        while (current != null && current.getKind() != ElementKind.PACKAGE) {
            if (current instanceof TypeElement te && !te.getModifiers().contains(Modifier.PUBLIC)) {
                return false;
            }
            current = current.getEnclosingElement();
        }
        return true;
    }

    private void writeMetamodelClass(TypeElement entityType) throws IOException {
        String entityQualifiedName = entityType.getQualifiedName().toString();
        String entitySimpleName = simpleName(entityType);
        String packageName = packageName(entityType);
        String generatedSimpleName = entitySimpleName + METAMODEL_SUFFIX;
        String generatedQualified = packageName.isEmpty()
                ? generatedSimpleName
                : packageName + "." + generatedSimpleName;

        JavaFileObject file = filer.createSourceFile(generatedQualified, entityType);
        try (Writer w = file.openWriter()) {
            if (!packageName.isEmpty()) {
                w.write("package " + packageName + ";\n\n");
            }
            w.write("import " + WORM_ATTRIBUTE_FQN + ";\n\n");
            w.write("/**\n");
            w.write(" * Static metamodel for {@link " + entityQualifiedName + "}.\n");
            w.write(" * Generated by WormMetamodelProcessor — do not edit.\n");
            w.write(" */\n");
            w.write("@javax.annotation.processing.Generated(\"br.com.liviacare.worm.processor.WormMetamodelProcessor\")\n");
            w.write("public final class " + generatedSimpleName + " {\n\n");
            w.write("    private " + generatedSimpleName + "() {}\n\n");

            for (Element member : collectPersistedMembers(entityType)) {
                String fieldName = member.getSimpleName().toString();
                String columnName = resolveColumnName(member);
                String upperSnake = toUpperSnake(fieldName);
                String typeLiteral = boxedTypeLiteral(member.asType().toString());

                // COLUMN_<UPPER_SNAKE> = "column_name"
                w.write("    /** DB column for {@code " + fieldName + "}. */\n");
                w.write("    public static final String COLUMN_" + upperSnake
                        + " = \"" + columnName + "\";\n");

                // WormAttribute<EntityType, FieldType> fieldName = new WormAttribute<>("column_name", Type.class);
                w.write("    public static final WormAttribute<" + entityQualifiedName + ", " + typeLiteral + "> "
                        + fieldName + " = new WormAttribute<>(\"" + columnName + "\", " + typeLiteral + ".class);\n\n");
            }

            w.write("}\n");
        }
    }

    private void writeQueryPathClass(TypeElement entityType) throws IOException {
        String entityQualifiedName = entityType.getQualifiedName().toString();
        String entitySimpleName = simpleName(entityType);
        String packageName = packageName(entityType);
        String generatedSimpleName = QUERY_PATH_PREFIX + entitySimpleName;
        String generatedQualified = packageName.isEmpty()
                ? generatedSimpleName
                : packageName + "." + generatedSimpleName;
        String tableName = resolveTableName(entityType);
        String staticFieldName = decapitalize(entitySimpleName);

        List<QueryPathFieldModel> fields = new ArrayList<>();
        for (Element member : collectPersistedMembers(entityType)) {
            QueryPathFieldModel model = resolveQueryPathField(member);
            if (model != null) fields.add(model);
        }

        JavaFileObject file = filer.createSourceFile(generatedQualified, entityType);
        try (Writer w = file.openWriter()) {
            if (!packageName.isEmpty()) {
                w.write("package " + packageName + ";\n\n");
            }
            w.write("import br.com.liviacare.worm.dsl.*;\n");
            w.write("import br.com.liviacare.worm.util.AliasUtils;\n\n");
            w.write("/**\n");
            w.write(" * Query path for {@link " + entityQualifiedName + "}.\n");
            w.write(" * Generated by WormMetamodelProcessor — do not edit.\n");
            w.write(" */\n");
            w.write("@javax.annotation.processing.Generated(\"br.com.liviacare.worm.processor.WormMetamodelProcessor\")\n");
            w.write("public final class " + generatedSimpleName + " extends EntityPath<" + entityQualifiedName + "> {\n\n");
            w.write("    public static final String TABLE = \"" + tableName + "\";\n");
            w.write("    public static final " + generatedSimpleName + " " + staticFieldName
                    + " = new " + generatedSimpleName + "(AliasUtils.defaultMainAlias(TABLE));\n\n");

            for (QueryPathFieldModel field : fields) {
                w.write("    public static final String COLUMN_" + toUpperSnake(field.fieldName)
                        + " = \"" + field.columnName + "\";\n");
            }
            if (!fields.isEmpty()) {
                w.write('\n');
            }

            for (QueryPathFieldModel field : fields) {
                w.write("    public final " + field.declarationType + " " + field.fieldName
                        + " = " + field.initializer + ";\n");
            }
            if (!fields.isEmpty()) {
                w.write('\n');
            }

            w.write("    public " + generatedSimpleName + "(String alias) {\n");
            w.write("        super(" + entityQualifiedName + ".class, TABLE, alias);\n");
            w.write("    }\n");
            w.write("}\n");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String resolveColumnName(Element member) {
        DbColumn dbColumn = member.getAnnotation(DbColumn.class);
        if (dbColumn != null && !dbColumn.value().isBlank()) return dbColumn.value();
        DbId dbId = member.getAnnotation(DbId.class);
        if (dbId != null && !dbId.value().isBlank()) return dbId.value();
        CreatedAt ca = member.getAnnotation(CreatedAt.class);
        if (ca != null) return ca.value().isBlank() ? "created_at" : ca.value();
        UpdatedAt ua = member.getAnnotation(UpdatedAt.class);
        if (ua != null) return ua.value().isBlank() ? "updated_at" : ua.value();
        DeletedAt da = member.getAnnotation(DeletedAt.class);
        if (da != null) return da.value().isBlank() ? "deleted_at" : da.value();
        Active ac = member.getAnnotation(Active.class);
        if (ac != null) return ac.value().isBlank() ? "active" : ac.value();
        if (member.getAnnotation(CreatedBy.class) != null) return "created_by";
        return member.getSimpleName().toString();
    }

    private String resolveTableName(TypeElement entityType) {
        DbTable table = entityType.getAnnotation(DbTable.class);
        if (table != null && !table.value().isBlank()) {
            return table.value();
        }
        return entityType.getSimpleName().toString().toLowerCase(Locale.ROOT);
    }

    private QueryPathFieldModel resolveQueryPathField(Element member) {
        String fieldName = member.getSimpleName().toString();
        String columnName = resolveColumnName(member);
        String boxedType = boxedTypeLiteral(member.asType().toString());
        String rawType = member.asType().toString();

        if ("java.lang.String".equals(boxedType)) {
            return new QueryPathFieldModel(fieldName, columnName, "StringPath", "string(\"" + columnName + "\")");
        }
        if ("boolean".equals(rawType) || "java.lang.Boolean".equals(rawType)) {
            return new QueryPathFieldModel(fieldName, columnName, "BooleanPath", "bool(\"" + columnName + "\")");
        }
        if ("java.util.UUID".equals(boxedType)) {
            return new QueryPathFieldModel(fieldName, columnName, "UuidPath", "uuid(\"" + columnName + "\")");
        }
        if (isEnumType(member)) {
            return new QueryPathFieldModel(
                    fieldName,
                    columnName,
                    "EnumPath<" + boxedType + ">",
                    "enumeration(\"" + columnName + "\", " + boxedType + ".class)"
            );
        }
        if (isNumericType(rawType, boxedType)) {
            return new QueryPathFieldModel(
                    fieldName,
                    columnName,
                    "NumberPath<" + boxedType + ">",
                    "number(\"" + columnName + "\", " + boxedType + ".class)"
            );
        }
        if (isDateType(boxedType)) {
            return new QueryPathFieldModel(
                    fieldName,
                    columnName,
                    "DatePath<" + boxedType + ">",
                    "date(\"" + columnName + "\", " + boxedType + ".class)"
            );
        }
        if (isDateTimeType(member)) {
            return new QueryPathFieldModel(
                    fieldName,
                    columnName,
                    "DateTimePath<" + boxedType + ">",
                    "dateTime(\"" + columnName + "\", " + boxedType + ".class)"
            );
        }
        if (isComparableType(member)) {
            return new QueryPathFieldModel(
                    fieldName,
                    columnName,
                    "ComparablePath<" + boxedType + ">",
                    "comparable(\"" + columnName + "\", " + boxedType + ".class)"
            );
        }
        return null;
    }

    private boolean isEnumType(Element member) {
        if (!(member.asType() instanceof javax.lang.model.type.DeclaredType declared)) return false;
        return declared.asElement().getKind() == ElementKind.ENUM;
    }

    private static boolean isNumericType(String rawType, String boxedType) {
        return "byte".equals(rawType) || "short".equals(rawType) || "int".equals(rawType) || "long".equals(rawType)
                || "float".equals(rawType) || "double".equals(rawType)
                || "java.lang.Byte".equals(boxedType) || "java.lang.Short".equals(boxedType)
                || "java.lang.Integer".equals(boxedType) || "java.lang.Long".equals(boxedType)
                || "java.lang.Float".equals(boxedType) || "java.lang.Double".equals(boxedType)
                || "java.math.BigDecimal".equals(boxedType) || "java.math.BigInteger".equals(boxedType);
    }

    private static boolean isDateType(String boxedType) {
        return "java.time.LocalDate".equals(boxedType);
    }

    private boolean isDateTimeType(Element member) {
        String t = boxedTypeLiteral(member.asType().toString());
        if ("java.time.LocalDate".equals(t)) return false;
        if (temporalType == null) return false;
        return types.isAssignable(types.erasure(member.asType()), types.erasure(temporalType));
    }

    private boolean isComparableType(Element member) {
        if (comparableType == null) return false;
        return types.isAssignable(types.erasure(member.asType()), types.erasure(comparableType));
    }

    /**
     * Collect persisted members for metamodel/query-path generation.
     *
     * For records, record components are preferred over synthetic backing fields,
     * avoiding duplicate declarations with the same logical name.
     */
    private static List<Element> collectPersistedMembers(TypeElement entityType) {
        Map<String, Element> byName = new LinkedHashMap<>();
        List<? extends Element> enclosed = entityType.getEnclosedElements();

        if (entityType.getKind() == ElementKind.RECORD) {
            for (Element element : enclosed) {
                if (element.getKind() == ElementKind.RECORD_COMPONENT) {
                    String name = element.getSimpleName().toString();
                    byName.putIfAbsent(name, element);
                }
            }
        }

        for (Element element : enclosed) {
            if (element.getKind() != ElementKind.FIELD) continue;
            VariableElement field = (VariableElement) element;
            if (field.getModifiers().contains(Modifier.STATIC)) continue;
            if (field.getAnnotation(DbJoin.class) != null) continue;
            String name = field.getSimpleName().toString();
            byName.putIfAbsent(name, field);
        }

        if (entityType.getKind() == ElementKind.RECORD) {
            // In case join annotations are placed only on record components, filter them too.
            byName.entrySet().removeIf(e -> e.getValue().getAnnotation(DbJoin.class) != null);
        }

        return new ArrayList<>(byName.values());
    }

    private static String toUpperSnake(String camel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c) && i > 0) sb.append('_');
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    private static String boxedTypeLiteral(String typeMirrorStr) {
        return switch (typeMirrorStr) {
            case "byte"    -> "java.lang.Byte";
            case "short"   -> "java.lang.Short";
            case "int"     -> "java.lang.Integer";
            case "long"    -> "java.lang.Long";
            case "float"   -> "java.lang.Float";
            case "double"  -> "java.lang.Double";
            case "char"    -> "java.lang.Character";
            case "boolean" -> "java.lang.Boolean";
            default        -> typeMirrorStr;
        };
    }

    private static String simpleName(TypeElement typeElement) {
        String pkg = packageName(typeElement);
        String qualified = typeElement.getQualifiedName().toString();
        if (pkg.isEmpty()) return qualified.replace('.', '_');
        String local = qualified.substring(pkg.length() + 1);
        return local.replace('.', '_');
    }

    private static String packageName(TypeElement typeElement) {
        Element parent = typeElement.getEnclosingElement();
        while (parent != null && !(parent instanceof PackageElement)) {
            parent = parent.getEnclosingElement();
        }
        return parent instanceof PackageElement p ? p.getQualifiedName().toString() : "";
    }

    private static String decapitalize(String value) {
        if (value == null || value.isBlank()) return "entity";
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private record QueryPathFieldModel(
            String fieldName,
            String columnName,
            String declarationType,
            String initializer
    ) {
    }
}
