package br.com.liviacare.worm.repository.query;

import br.com.liviacare.worm.annotation.query.Query;
import br.com.liviacare.worm.annotation.query.QueryParam;
import br.com.liviacare.worm.orm.OrmOperations;
import br.com.liviacare.worm.query.Pageable;
import br.com.liviacare.worm.query.Slice;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryRepositoryFactoryTest {

    interface NamedQueryRepository {
        @Query("select id, name from users where active = :active")
        List<TestUser> findAllActive(@QueryParam("active") Boolean active);
    }

    interface OptionalQueryRepository {
        @Query("select id, name from users where email = :email")
        Optional<TestUser> findByEmail(@QueryParam("email") String email);
    }

    interface SliceQueryRepository {
        @Query("select id, name from users")
        Slice<TestUser> findPage(Pageable pageable);
    }

    @Test
    void listQueryBindsNamedParameters() {
        RecordingOrmOperations orm = new RecordingOrmOperations(List.of(new TestUser(UUID.randomUUID(), "A")));
        NamedQueryRepository repository = QueryRepositoryFactory.create(NamedQueryRepository.class, orm.proxy());
        List<TestUser> result = repository.findAllActive(true);

        assertEquals(1, result.size());
        assertEquals("select id, name from users where active = ?", orm.executedSql);
        assertEquals(List.of(true), orm.executedParams);
    }

    @Test
    void optionalQueryReturnsEmptyWhenNoRows() {
        RecordingOrmOperations orm = new RecordingOrmOperations(List.of());
        OptionalQueryRepository repository = QueryRepositoryFactory.create(OptionalQueryRepository.class, orm.proxy());

        Optional<TestUser> result = repository.findByEmail("foo");

        assertTrue(result.isEmpty());
        assertEquals("select id, name from users where email = ?", orm.executedSql);
        assertEquals(List.of("foo"), orm.executedParams);
    }

    @Test
    void sliceQueryAddsPagination() {
        List<TestUser> rows = List.of(
                new TestUser(UUID.randomUUID(), "A"),
                new TestUser(UUID.randomUUID(), "B"),
                new TestUser(UUID.randomUUID(), "C")
        );
        RecordingOrmOperations orm = new RecordingOrmOperations(rows);
        SliceQueryRepository repository = QueryRepositoryFactory.create(SliceQueryRepository.class, orm.proxy());

        Slice<TestUser> slice = repository.findPage(Pageable.of(0, 2));

        assertEquals(2, slice.content().size());
        assertTrue(slice.hasNext());
        assertEquals("select id, name from users LIMIT ? OFFSET ?", orm.executedSql);
        assertEquals(List.of(3, 0L), orm.executedParams);
    }

    private static final class RecordingOrmOperations implements InvocationHandler {
        private final List<?> response;
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
                switch (method.getName()) {
                    case "toString":
                        return "recording-orm";
                    case "equals":
                        return proxy == args[0];
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    default:
                        throw new UnsupportedOperationException(method.getName());
                }
            }
            if ("executeRaw".equals(method.getName())) {
                executedSql = (String) args[0];
                Object rawParams = args.length > 2 ? args[2] : null;
                executedParams = rawParams instanceof Object[] array ? Arrays.asList(array) : List.of();
                return new ArrayList<>(response);
            }
            throw new UnsupportedOperationException(method.getName());
        }
    }
}



