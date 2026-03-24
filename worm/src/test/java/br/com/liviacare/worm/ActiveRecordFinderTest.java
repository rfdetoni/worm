package br.com.liviacare.worm;

import br.com.liviacare.worm.orm.OrmManagerLocator;
import br.com.liviacare.worm.orm.OrmOperations;
import br.com.liviacare.worm.query.FilterBuilder;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveRecordFinderTest {

    @Test
    void activeRecordInfersEntityClassAndExposesFinderDefaults() {
        RecordingOrm orm = new RecordingOrm();
        OrmManagerLocator.setOrmManager(orm.proxy());

        UUID id = UUID.randomUUID();
        Book book = new Book(id, "Clean Architecture");

        assertEquals(Book.class, book.entityClass());

        Optional<Book> found = book.byId(id);
        assertTrue(found.isPresent());
        assertEquals(Book.class, orm.lastClass);
        assertEquals(id, orm.lastId);

        List<Book> all = book.all(FilterBuilder.create().eq("title", "Clean Architecture"));
        assertEquals(1, all.size());
        assertEquals(Book.class, orm.lastClass);

        long count = book.count();
        assertEquals(1L, count);

        boolean exists = book.exists();
        assertTrue(exists);
    }

    private static final class Book extends ActiveRecord<Book, UUID> {
        private final UUID id;
        private final String title;

        private Book(UUID id, String title) {
            this.id = id;
            this.title = title;
        }

        @Override
        public UUID getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }
    }

    private static final class RecordingOrm implements InvocationHandler {
        private Class<?> lastClass;
        private Object lastId;

        OrmOperations proxy() {
            return (OrmOperations) Proxy.newProxyInstance(
                    OrmOperations.class.getClassLoader(),
                    new Class[]{OrmOperations.class},
                    this
            );
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "recording-orm";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }

            switch (method.getName()) {
                case "findById":
                    lastClass = (Class<?>) args[0];
                    lastId = args[1];
                    return Optional.of(new Book((UUID) args[1], "Clean Architecture"));
                case "findAll":
                    lastClass = (Class<?>) args[0];
                    return List.of(new Book(UUID.randomUUID(), "Clean Architecture"));
                case "count":
                    lastClass = (Class<?>) args[0];
                    return 1L;
                case "exists":
                    lastClass = (Class<?>) args[0];
                    return true;
                case "findOne":
                    lastClass = (Class<?>) args[0];
                    return Optional.of(new Book(UUID.randomUUID(), "Clean Architecture"));
                default:
                    throw new UnsupportedOperationException("Unexpected method: " + method.getName());
            }
        }
    }
}

