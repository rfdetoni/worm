package br.com.liviacare.worm.processor;

import br.com.liviacare.worm.annotation.query.JlfQuery;
import br.com.liviacare.worm.annotation.query.Query;
import br.com.liviacare.worm.annotation.query.QueryParam;
import br.com.liviacare.worm.annotation.query.QueryRepository;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.*;

/**
 * Generates concrete query repository implementations for interfaces annotated with @QueryRepository.
 */
@SupportedAnnotationTypes("br.com.liviacare.worm.annotation.query.QueryRepository")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class WormQueryRepositoryProcessor extends AbstractProcessor {

    private static final String IMPL_PACKAGE = "br.com.liviacare.worm.generated.repository";
    private static final String SERVICE_PATH = "META-INF/services/br.com.liviacare.worm.repository.query.GeneratedQueryRepositoryProvider";

    private Filer filer;
    private Messager messager;
    private final Set<String> providerClassNames = new LinkedHashSet<>();

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.filer = processingEnv.getFiler();
        this.messager = processingEnv.getMessager();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(QueryRepository.class)) {
            if (!(element instanceof TypeElement repoType) || repoType.getKind() != ElementKind.INTERFACE) {
                continue;
            }
            try {
                String providerName = writeImplementation(repoType);
                providerClassNames.add(providerName);
            } catch (IOException e) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "Failed to generate query repository implementation for " + repoType.getQualifiedName() + ": " + e.getMessage(),
                        repoType);
            }
        }

        if (roundEnv.processingOver() && !providerClassNames.isEmpty()) {
            try {
                FileObject file = filer.createResource(StandardLocation.CLASS_OUTPUT, "", SERVICE_PATH);
                try (Writer writer = file.openWriter()) {
                    for (String provider : providerClassNames) {
                        writer.write(provider);
                        writer.write('\n');
                    }
                }
            } catch (IOException e) {
                messager.printMessage(Diagnostic.Kind.ERROR, "Failed to generate repository provider service file: " + e.getMessage());
            }
        }
        return false;
    }

    private String writeImplementation(TypeElement repoType) throws IOException {
        String interfaceType = repoType.getQualifiedName().toString();
        String baseName = simpleName(repoType).replace('.', '_');
        String implName = baseName + "_WormRepositoryImpl";
        String providerName = baseName + "_WormRepositoryProvider";
        String implQualifiedName = IMPL_PACKAGE + "." + implName;
        String providerQualifiedName = IMPL_PACKAGE + "." + providerName;

        JavaFileObject implFile = filer.createSourceFile(implQualifiedName, repoType);
        try (Writer writer = implFile.openWriter()) {
            writer.write("package " + IMPL_PACKAGE + ";\n\n");
            writer.write("import br.com.liviacare.worm.orm.OrmOperations;\n");
            writer.write("import br.com.liviacare.worm.query.Pageable;\n");
            writer.write("import br.com.liviacare.worm.query.Slice;\n");
            writer.write("import java.util.ArrayList;\n");
            writer.write("import java.util.List;\n");
            writer.write("import java.util.Optional;\n\n");
            writer.write("public final class " + implName + " implements " + interfaceType + " {\n");
            writer.write("    private final OrmOperations ormOperations;\n\n");
            writer.write("    public " + implName + "(OrmOperations ormOperations) {\n");
            writer.write("        this.ormOperations = ormOperations;\n");
            writer.write("    }\n\n");

            writeObjectMethods(writer, interfaceType);

            for (Element enclosed : repoType.getEnclosedElements()) {
                if (!(enclosed instanceof ExecutableElement method)) {
                    continue;
                }
                if (!method.getModifiers().contains(Modifier.ABSTRACT)) {
                    continue;
                }
                writeMethod(writer, method, interfaceType);
            }

            writer.write("}\n");
        }

        JavaFileObject providerFile = filer.createSourceFile(providerQualifiedName, repoType);
        try (Writer writer = providerFile.openWriter()) {
            writer.write("package " + IMPL_PACKAGE + ";\n\n");
            writer.write("import br.com.liviacare.worm.orm.OrmOperations;\n");
            writer.write("import br.com.liviacare.worm.repository.query.GeneratedQueryRepositoryProvider;\n\n");
            writer.write("public final class " + providerName + " implements GeneratedQueryRepositoryProvider<" + interfaceType + "> {\n");
            writer.write("    @Override\n");
            writer.write("    public Class<" + interfaceType + "> repositoryInterface() {\n");
            writer.write("        return " + interfaceType + ".class;\n");
            writer.write("    }\n\n");
            writer.write("    @Override\n");
            writer.write("    public " + interfaceType + " create(OrmOperations ormOperations) {\n");
            writer.write("        return new " + implName + "(ormOperations);\n");
            writer.write("    }\n");
            writer.write("}\n");
        }

        return providerQualifiedName;
    }

    private static void writeObjectMethods(Writer writer, String interfaceType) throws IOException {
        writer.write("    @Override\n");
        writer.write("    public String toString() {\n");
        writer.write("        return \"" + interfaceType + "#generated-query-repository\";\n");
        writer.write("    }\n\n");
        writer.write("    @Override\n");
        writer.write("    public boolean equals(Object other) {\n");
        writer.write("        return this == other;\n");
        writer.write("    }\n\n");
        writer.write("    @Override\n");
        writer.write("    public int hashCode() {\n");
        writer.write("        return System.identityHashCode(this);\n");
        writer.write("    }\n\n");
    }

    private void writeMethod(Writer writer, ExecutableElement method, String interfaceType) throws IOException {
        String returnType = method.getReturnType().toString();
        String methodName = method.getSimpleName().toString();
        List<? extends VariableElement> params = method.getParameters();
        List<? extends javax.lang.model.type.TypeMirror> thrown = method.getThrownTypes();
        QueryPlan plan = analyzeMethod(method);

        writer.write("    @Override\n");
        writer.write("    public " + returnType + " " + methodName + "(");
        for (int i = 0; i < params.size(); i++) {
            VariableElement p = params.get(i);
            if (i > 0) writer.write(", ");
            writer.write(p.asType().toString() + " p" + i);
        }
        writer.write(")");
        if (!thrown.isEmpty()) {
            writer.write(" throws ");
            for (int i = 0; i < thrown.size(); i++) {
                if (i > 0) writer.write(", ");
                writer.write(thrown.get(i).toString());
            }
        }
        writer.write(" {\n");
        if (plan == null) {
            writer.write("        throw new UnsupportedOperationException(\"Method " + methodName + " is not annotated with @Query\");\n");
            writer.write("    }\n\n");
            return;
        }

        if (plan.returnKind == QueryReturnKindModel.SLICE) {
            writer.write("        Pageable pageable = p" + plan.pageableIndex + ";\n");
            writer.write("        if (pageable == null) throw new IllegalArgumentException(\"Slice queries require a non-null Pageable argument\");\n");
            writer.write("        if (pageable.pageSize() <= 0) throw new IllegalArgumentException(\"Pageable pageSize must be greater than zero\");\n");
            writer.write("        List<" + plan.resultType + "> results = ormOperations.executeRawPaged(\n");
            writer.write("                \"" + escapeJava(plan.sql) + "\",\n");
            writer.write("                " + plan.resultType + ".class,\n");
            writer.write("                pageable.pageSize() + 1,\n");
            writer.write("                pageable.getOffset(),\n");
            writer.write("                new Object[]{" + parameterArray(plan.parameterIndexes) + "}\n");
            writer.write("        );\n");
            writer.write("        boolean hasNext = results.size() > pageable.pageSize();\n");
            writer.write("        List<" + plan.resultType + "> content = hasNext ? new ArrayList<>(results.subList(0, pageable.pageSize())) : results;\n");
            writer.write("        return new Slice<>(content, pageable, hasNext);\n");
        } else if (plan.returnKind == QueryReturnKindModel.OPTIONAL) {
            writer.write("        List<" + plan.resultType + "> results = ormOperations.executeRaw(\n");
            writer.write("                \"" + escapeJava(plan.sql) + "\",\n");
            writer.write("                " + plan.resultType + ".class,\n");
            writer.write("                new Object[]{" + parameterArray(plan.parameterIndexes) + "}\n");
            writer.write("        );\n");
            writer.write("        return results.isEmpty() ? Optional.empty() : Optional.ofNullable(results.get(0));\n");
        } else if (plan.returnKind == QueryReturnKindModel.SCALAR) {
            String boxClass = boxTypeStr(plan.resultType);
            writer.write("        List<" + boxClass + "> results = ormOperations.executeRaw(\n");
            writer.write("                \"" + escapeJava(plan.sql) + "\",\n");
            writer.write("                " + boxClass + ".class,\n");
            writer.write("                new Object[]{" + parameterArray(plan.parameterIndexes) + "}\n");
            writer.write("        );\n");
            if (isPrimitiveStr(plan.resultType)) {
                writer.write("        if (results.isEmpty() || results.get(0) == null) throw new IllegalStateException(\"Query returned null but method returns primitive " + plan.resultType + "\");\n");
                writer.write("        return results.get(0);\n");
            } else {
                writer.write("        return results.isEmpty() ? null : results.get(0);\n");
            }
        } else {
            writer.write("        return ormOperations.executeRaw(\n");
            writer.write("                \"" + escapeJava(plan.sql) + "\",\n");
            writer.write("                " + plan.resultType + ".class,\n");
            writer.write("                new Object[]{" + parameterArray(plan.parameterIndexes) + "}\n");
            writer.write("        );\n");
        }
        writer.write("    }\n\n");
    }

    private static boolean isPrimitiveStr(String type) {
        return switch (type) {
            case "boolean", "byte", "short", "int", "long", "char", "float", "double" -> true;
            default -> false;
        };
    }

    private static String boxTypeStr(String type) {
        return switch (type) {
            case "boolean" -> "Boolean";
            case "byte" -> "Byte";
            case "short" -> "Short";
            case "int" -> "Integer";
            case "long" -> "Long";
            case "char" -> "Character";
            case "float" -> "Float";
            case "double" -> "Double";
            default -> type;
        };
    }

    private QueryPlan analyzeMethod(ExecutableElement method) {
        Query query = method.getAnnotation(Query.class);
        String sql = query != null ? query.value() : null;
        if (sql == null || sql.isBlank()) {
            JlfQuery jlfQuery = method.getAnnotation(JlfQuery.class);
            sql = jlfQuery != null ? jlfQuery.value() : null;
        }
        if (sql == null || sql.isBlank()) {
            return null;
        }

        ParsedSql parsedSql = parseNamedSql(sql);
        QueryReturnKindModel returnKind = QueryReturnKindModel.of(method);
        String resultType = extractResultType(method, returnKind);

        Map<String, Integer> parameterByName = new HashMap<>();
        int pageableIndex = -1;
        List<? extends VariableElement> params = method.getParameters();
        for (int i = 0; i < params.size(); i++) {
            VariableElement parameter = params.get(i);
            if ("br.com.liviacare.worm.query.Pageable".equals(parameter.asType().toString())) {
                pageableIndex = i;
                continue;
            }
            QueryParam queryParam = parameter.getAnnotation(QueryParam.class);
            String name = queryParam != null && !queryParam.value().isBlank() ? queryParam.value() : parameter.getSimpleName().toString();
            parameterByName.put(name, i);
        }

        int[] indexes = new int[parsedSql.parameterNames.size()];
        for (int i = 0; i < parsedSql.parameterNames.size(); i++) {
            String name = parsedSql.parameterNames.get(i);
            Integer index = parameterByName.get(name);
            if (index == null) {
                throw new IllegalStateException("@Query references parameter '" + name + "' not declared in method " + method.getSimpleName());
            }
            indexes[i] = index;
        }
        if (returnKind == QueryReturnKindModel.SLICE && pageableIndex < 0) {
            throw new IllegalStateException("Slice queries must declare a Pageable parameter: " + method.getSimpleName());
        }
        return new QueryPlan(parsedSql.sql, indexes, returnKind, resultType, pageableIndex);
    }

    private static ParsedSql parseNamedSql(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        List<String> names = new ArrayList<>();
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == ':' && (i == 0 || sql.charAt(i - 1) != ':')) {
                int start = i + 1;
                int j = start;
                while (j < sql.length()) {
                    char c = sql.charAt(j);
                    if (!Character.isLetterOrDigit(c) && c != '_') break;
                    j++;
                }
                if (j > start) {
                    names.add(sql.substring(start, j));
                    out.append('?');
                    i = j - 1;
                    continue;
                }
            }
            out.append(ch);
        }
        return new ParsedSql(out.toString(), names);
    }

    private static String extractResultType(ExecutableElement method, QueryReturnKindModel kind) {
        if (kind == QueryReturnKindModel.SCALAR) {
            return method.getReturnType().toString();
        }
        TypeMirror returnType = method.getReturnType();
        if (!(returnType instanceof DeclaredType declaredType) || declaredType.getTypeArguments().isEmpty()) {
            throw new IllegalStateException("Unable to resolve result type for method " + method.getSimpleName());
        }
        return declaredType.getTypeArguments().get(0).toString();
    }

    private static String parameterArray(int[] indexes) {
        if (indexes.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < indexes.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append("p").append(indexes[i]);
        }
        return sb.toString();
    }

    private static String escapeJava(String input) {
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record ParsedSql(String sql, List<String> parameterNames) {
    }

    private record QueryPlan(String sql, int[] parameterIndexes, QueryReturnKindModel returnKind, String resultType, int pageableIndex) {
    }

    private enum QueryReturnKindModel {
        LIST,
        OPTIONAL,
        SLICE,
        SCALAR;

        static QueryReturnKindModel of(ExecutableElement method) {
            String raw = method.getReturnType().toString();
            if (raw.startsWith("java.util.List<")) return LIST;
            if (raw.startsWith("java.util.Optional<")) return OPTIONAL;
            if (raw.startsWith("br.com.liviacare.worm.query.Slice<")) return SLICE;
            if ("void".equals(raw)) throw new IllegalStateException("@Query method cannot return void: " + method.getSimpleName());
            return SCALAR;
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
        return qualified.substring(packageName.length() + 1);
    }
}
