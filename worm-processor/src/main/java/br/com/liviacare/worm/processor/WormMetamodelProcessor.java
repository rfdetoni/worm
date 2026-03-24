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
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
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

    private Filer filer;
    private Messager messager;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.filer = processingEnv.getFiler();
        this.messager = processingEnv.getMessager();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(DbTable.class)) {
            if (!(element instanceof TypeElement entityType)) continue;
            if (!entityType.getModifiers().contains(Modifier.PUBLIC) || !isGeneratedTypeAccessible(entityType)) continue;
            try {
                writeMetamodelClass(entityType);
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

            for (Element enclosed : entityType.getEnclosedElements()) {
                if (enclosed.getKind() != ElementKind.FIELD) continue;
                VariableElement field = (VariableElement) enclosed;
                if (field.getModifiers().contains(Modifier.STATIC)) continue;
                if (field.getAnnotation(DbJoin.class) != null) continue; // skip join fields

                String fieldName = field.getSimpleName().toString();
                String columnName = resolveColumnName(field);
                String upperSnake = toUpperSnake(fieldName);
                String typeLiteral = boxedTypeLiteral(field.asType().toString());

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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String resolveColumnName(VariableElement field) {
        DbColumn dbColumn = field.getAnnotation(DbColumn.class);
        if (dbColumn != null && !dbColumn.value().isBlank()) return dbColumn.value();
        DbId dbId = field.getAnnotation(DbId.class);
        if (dbId != null && !dbId.value().isBlank()) return dbId.value();
        CreatedAt ca = field.getAnnotation(CreatedAt.class);
        if (ca != null) return ca.value().isBlank() ? "created_at" : ca.value();
        UpdatedAt ua = field.getAnnotation(UpdatedAt.class);
        if (ua != null) return ua.value().isBlank() ? "updated_at" : ua.value();
        DeletedAt da = field.getAnnotation(DeletedAt.class);
        if (da != null) return da.value().isBlank() ? "deleted_at" : da.value();
        Active ac = field.getAnnotation(Active.class);
        if (ac != null) return ac.value().isBlank() ? "active" : ac.value();
        if (field.getAnnotation(CreatedBy.class) != null) return "created_by";
        return field.getSimpleName().toString();
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
}

