package br.com.liviacare.worm.repository.query;

import br.com.liviacare.worm.annotation.query.Query;
import br.com.liviacare.worm.orm.OrmOperations;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
class GeneratedQueryRepositoryLookupTest {

    public interface GeneratedRepo {
        @Query("select 1")
        List<String> names();
    }

    static final class GeneratedRepoImpl implements GeneratedRepo {
        private final OrmOperations ormOperations;

        GeneratedRepoImpl(OrmOperations ormOperations) {
            this.ormOperations = ormOperations;
        }

        @Override
        public List<String> names() {
            return ormOperations.executeRaw("select ?", String.class, new Object[]{"generated"});
        }
    }

    public static final class GeneratedRepoProvider implements GeneratedQueryRepositoryProvider<GeneratedRepo> {
        @Override
        public Class<GeneratedRepo> repositoryInterface() {
            return GeneratedRepo.class;
        }

        @Override
        public GeneratedRepo create(OrmOperations ormOperations) {
            return new GeneratedRepoImpl(ormOperations);
        }
    }

    @Test
    void factoryPrefersGeneratedRepositoryProviderWhenAvailable() {
        RecordingOrmOperations handler = new RecordingOrmOperations(List.of("generated"));
        OrmOperations orm = handler.proxy();

        GeneratedRepo repo = QueryRepositoryFactory.create(GeneratedRepo.class, orm);

        assertNotNull(repo);
        assertEquals(List.of("generated"), repo.names());
        assertFalse(Proxy.isProxyClass(repo.getClass()));
        assertEquals("executeRaw", handler.executedMethod);
        assertEquals("select ?", handler.executedSql);
        assertEquals(List.of("generated"), handler.executedParams);
    }

    private static final class RecordingOrmOperations implements InvocationHandler {
        private final List<?> response;
        private String executedMethod;
        private String executedSql;
        private List<Object> executedParams;

        private RecordingOrmOperations(List<?> response) {
            this.response = response;
        }

        OrmOperations proxy() {
            return (OrmOperations) Proxy.newProxyInstance(
                    OrmOperations.class.getClassLoader(),
                    new Class[]{OrmOperations.class},
                    this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "generated-query-orm";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
            if ("executeRaw".equals(method.getName())) {
                executedMethod = method.getName();
                executedSql = (String) args[0];
                Object rawParams = args.length > 2 ? args[2] : null;
                executedParams = rawParams instanceof Object[] array ? Arrays.asList(array) : List.of();
                return new ArrayList<>(response);
            }
            throw new UnsupportedOperationException(method.getName());
        }
    }
}

