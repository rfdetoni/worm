package br.com.liviacare.worm.repository.query;

import br.com.liviacare.worm.annotation.query.JlfQuery;
import br.com.liviacare.worm.annotation.query.Query;
import br.com.liviacare.worm.annotation.query.QueryParam;
import br.com.liviacare.worm.orm.OrmManagerLocator;
import br.com.liviacare.worm.orm.OrmOperations;
import br.com.liviacare.worm.query.Pageable;
import br.com.liviacare.worm.query.Slice;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Factory that builds repository proxies whose methods can execute native SQL annotated with {@link Query}.
 */
public final class QueryRepositoryFactory {

    private QueryRepositoryFactory() {}

    public static <T> T create(Class<T> repositoryInterface) {
        return create(repositoryInterface, OrmManagerLocator.getOrmManager());
    }

    public static <T> T create(Class<T> repositoryInterface, OrmOperations ormOperations) {
        Objects.requireNonNull(repositoryInterface, "repositoryInterface must not be null");
        Objects.requireNonNull(ormOperations, "ormOperations must not be null");
        if (!repositoryInterface.isInterface()) {
            throw new IllegalArgumentException("Repository must be an interface");
        }
        QueryRepositoryInvocationHandler handler = new QueryRepositoryInvocationHandler(repositoryInterface, ormOperations);
        @SuppressWarnings("unchecked")
        T proxy = (T) Proxy.newProxyInstance(
                repositoryInterface.getClassLoader(),
                new Class[]{repositoryInterface},
                handler);
        return proxy;
    }

    private static final class QueryRepositoryInvocationHandler implements InvocationHandler {
        private final Class<?> repositoryInterface;
        private final OrmOperations ormOperations;
        private final Map<Method, NativeQueryMethod> queryMethods;

        private QueryRepositoryInvocationHandler(Class<?> repositoryInterface, OrmOperations ormOperations) {
            this.repositoryInterface = repositoryInterface;
            this.ormOperations = ormOperations;
            this.queryMethods = buildQueryMethods(repositoryInterface);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return handleObjectMethod(proxy, method, args);
            }
            if (method.isDefault()) {
                return invokeDefaultMethod(proxy, method, args);
            }
            NativeQueryMethod queryMethod = queryMethods.get(method);
            if (queryMethod == null) {
                throw new UnsupportedOperationException("Method " + method.getName() + " is not annotated with @Query");
            }
            return queryMethod.execute(args, ormOperations);
        }

        private Map<Method, NativeQueryMethod> buildQueryMethods(Class<?> repositoryInterface) {
            Map<Method, NativeQueryMethod> methods = new HashMap<>();
            for (Method method : repositoryInterface.getMethods()) {
                Query query = method.getAnnotation(Query.class);
                if (query == null) {
                    JlfQuery jlfQuery = method.getAnnotation(JlfQuery.class);
                    if (jlfQuery != null) {
                        methods.put(method, NativeQueryMethod.from(method, jlfQuery.value()));
                    }
                } else {
                    methods.put(method, NativeQueryMethod.from(method, query.value()));
                }
            }
            return methods;
        }

        private Object handleObjectMethod(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "toString":
                    return repositoryInterface.getName() + "#query-proxy";
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                default:
                    throw new UnsupportedOperationException("Unsupported Object method: " + method.getName());
            }
        }

        private Object invokeDefaultMethod(Object proxy, Method method, Object[] args) throws Throwable {
            Constructor<MethodHandles.Lookup> constructor = MethodHandles.Lookup.class
                    .getDeclaredConstructor(Class.class, int.class);
            constructor.setAccessible(true);
            MethodHandles.Lookup lookup = constructor.newInstance(method.getDeclaringClass(), MethodHandles.Lookup.PRIVATE);
            MethodHandle handle = lookup.unreflectSpecial(method, method.getDeclaringClass()).bindTo(proxy);
            return handle.invokeWithArguments(args != null ? args : new Object[0]);
        }
    }

    private static final class NativeQueryMethod {
        private final Method method;
        private final String parsedSql;
        private final List<String> parameterNames;
        private final QueryReturnKind returnKind;
        private final Class<?> resultType;
        private final Map<String, ParameterDescriptor> parameterByName;
        private final int pageableIndex;

        private NativeQueryMethod(Method method,
                                  String parsedSql,
                                  List<String> parameterNames,
                                  QueryReturnKind returnKind,
                                  Class<?> resultType,
                                  Map<String, ParameterDescriptor> parameterByName,
                                  int pageableIndex) {
            this.method = method;
            this.parsedSql = parsedSql;
            this.parameterNames = parameterNames;
            this.returnKind = returnKind;
            this.resultType = resultType;
            this.parameterByName = parameterByName;
            this.pageableIndex = pageableIndex;
        }

        static NativeQueryMethod from(Method method, String sql) {
            if (sql == null || sql.isBlank()) {
                throw new IllegalArgumentException("@Query value must not be blank for method " + method.getName());
            }
            NamedSql namedSql = NamedSql.parse(sql);
            QueryReturnKind returnKind = QueryReturnKind.of(method);
            Class<?> resultType = returnKind.extractResultType(method);
            Parameter[] parameters = method.getParameters();
            int pageableIndex = -1;
            Map<String, ParameterDescriptor> parameterByName = new HashMap<>();
            for (int i = 0; i < parameters.length; i++) {
                Parameter parameter = parameters[i];
                if (Pageable.class.isAssignableFrom(parameter.getType())) {
                    if (returnKind != QueryReturnKind.SLICE) {
                        throw new IllegalArgumentException("Pageable parameter is only allowed when returning a Slice");
                    }
                    if (pageableIndex >= 0) {
                        throw new IllegalArgumentException("Only one Pageable parameter is allowed");
                    }
                    pageableIndex = i;
                    continue;
                }
                String name = resolveParameterName(parameter, i);
                if (parameterByName.put(name, new ParameterDescriptor(i, name)) != null) {
                    throw new IllegalArgumentException("Duplicate parameter name: " + name);
                }
            }
            if (returnKind == QueryReturnKind.SLICE && pageableIndex < 0) {
                throw new IllegalArgumentException("Slice queries must accept a Pageable argument");
            }
            if (returnKind != QueryReturnKind.SLICE && pageableIndex >= 0) {
                throw new IllegalArgumentException("Pageable arguments are only supported for Slice return types");
            }
            Set<String> sqlParamNames = new HashSet<>(namedSql.parameterNames());
            if (!parameterByName.keySet().equals(sqlParamNames)) {
                Set<String> missingInQuery = new HashSet<>(parameterByName.keySet());
                missingInQuery.removeAll(sqlParamNames);
                Set<String> missingInMethod = new HashSet<>(sqlParamNames);
                missingInMethod.removeAll(parameterByName.keySet());
                if (!missingInQuery.isEmpty()) {
                    throw new IllegalArgumentException("Parameters " + missingInQuery + " are declared but not used in @Query");
                }
                if (!missingInMethod.isEmpty()) {
                    throw new IllegalArgumentException("@Query references parameters " + missingInMethod + " that are not declared");
                }
            }
            return new NativeQueryMethod(method, namedSql.sql(), Collections.unmodifiableList(namedSql.parameterNames()), returnKind, resultType, parameterByName, pageableIndex);
        }

        Object execute(Object[] args, OrmOperations ormOperations) {
            Object[] actualArgs = args == null ? new Object[0] : args;
            List<Object> resolvedParameters = resolveParameters(actualArgs);
            switch (returnKind) {
                case LIST -> {
                    return ormOperations.executeRaw(parsedSql, resultType, resolvedParameters.toArray());
                }
                case OPTIONAL -> {
                    List<?> results = ormOperations.executeRaw(parsedSql, resultType, resolvedParameters.toArray());
                    return results.isEmpty() ? Optional.empty() : Optional.ofNullable(results.get(0));
                }
                case SLICE -> {
                    Pageable pageable = requirePageable(actualArgs);
                    int pageSize = pageable.pageSize();
                    if (pageSize <= 0) {
                        throw new IllegalArgumentException("Pageable pageSize must be greater than zero");
                    }
                    long offset = pageable.getOffset();
                    String paginatedSql = parsedSql + " LIMIT ? OFFSET ?";
                    List<Object> pagedParameters = new ArrayList<>(resolvedParameters.size() + 2);
                    pagedParameters.addAll(resolvedParameters);
                    pagedParameters.add(pageSize + 1);
                    pagedParameters.add(offset);
                    List<?> results = ormOperations.executeRaw(paginatedSql, resultType, pagedParameters.toArray());
                    boolean hasNext = results.size() > pageSize;
                    List<?> content = hasNext ? new ArrayList<>(results.subList(0, pageSize)) : new ArrayList<>(results);
                    return new Slice<>(content, pageable, hasNext);
                }
                default -> throw new IllegalStateException("Unsupported return kind: " + returnKind);
            }
        }

        private List<Object> resolveParameters(Object[] args) {
            if (parameterNames.isEmpty()) {
                return Collections.emptyList();
            }
            List<Object> resolved = new ArrayList<>(parameterNames.size());
            for (String name : parameterNames) {
                ParameterDescriptor descriptor = parameterByName.get(name);
                resolved.add(descriptor.valueFrom(args));
            }
            return resolved;
        }

        private Pageable requirePageable(Object[] args) {
            Object value = pageableIndex >= 0 && pageableIndex < args.length ? args[pageableIndex] : null;
            if (!(value instanceof Pageable pageable)) {
                throw new IllegalArgumentException("Slice queries require a non-null Pageable argument");
            }
            return pageable;
        }

        private static String resolveParameterName(Parameter parameter, int position) {
            QueryParam annotation = parameter.getAnnotation(QueryParam.class);
            if (annotation != null && !annotation.value().isBlank()) {
                return annotation.value();
            }
            if (!parameter.isNamePresent()) {
                throw new IllegalStateException("Parameter names are not available at runtime. Recompile with -parameters or add @QueryParam to method parameter "
                        + position);
            }
            return parameter.getName();
        }
    }

    private static final class ParameterDescriptor {
        private final int index;
        private final String name;

        private ParameterDescriptor(int index, String name) {
            this.index = index;
            this.name = name;
        }

        private Object valueFrom(Object[] args) {
            if (index < 0 || index >= args.length) return null;
            return args[index];
        }
    }

    private enum QueryReturnKind {
        LIST, OPTIONAL, SLICE;

        private static QueryReturnKind of(Method method) {
            Class<?> raw = method.getReturnType();
            if (List.class.isAssignableFrom(raw)) return LIST;
            if (Optional.class.isAssignableFrom(raw)) return OPTIONAL;
            if (Slice.class.isAssignableFrom(raw)) return SLICE;
            throw new IllegalArgumentException("@Query method must return Optional, List or Slice: " + method.getName());
        }

        private Class<?> extractResultType(Method method) {
            Type generic = method.getGenericReturnType();
            if (generic instanceof ParameterizedType pt) {
                Type[] args = pt.getActualTypeArguments();
                if (args.length != 1) {
                    throw new IllegalArgumentException("Return type must declare exactly one generic argument");
                }
                Type target = args[0];
                if (target instanceof Class<?> cls) {
                    return cls;
                }
                if (target instanceof ParameterizedType parameterizedTarget) {
                    Type raw = parameterizedTarget.getRawType();
                    if (raw instanceof Class<?> rawClass) {
                        return rawClass;
                    }
                }
            }
            throw new IllegalArgumentException("Unable to resolve result type for method " + method.getName());
        }
    }

    private static final class NamedSql {
        private static final Pattern PARAMETER = Pattern.compile("(?<!:):(\\w+)");
        private final String sql;
        private final List<String> parameterNames;

        private NamedSql(String sql, List<String> parameterNames) {
            this.sql = sql;
            this.parameterNames = parameterNames;
        }

        static NamedSql parse(String value) {
            Matcher matcher = PARAMETER.matcher(value);
            StringBuilder builder = new StringBuilder();
            List<String> names = new ArrayList<>();
            int position = 0;
            while (matcher.find()) {
                builder.append(value, position, matcher.start());
                builder.append('?');
                names.add(matcher.group(1));
                position = matcher.end();
            }
            builder.append(value.substring(position));
            return new NamedSql(builder.toString(), names);
        }

        String sql() {
            return sql;
        }

        List<String> parameterNames() {
            return parameterNames;
        }
    }
}


