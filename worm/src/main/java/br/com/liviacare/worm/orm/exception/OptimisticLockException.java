package br.com.liviacare.worm.orm.exception;

public class OptimisticLockException extends RuntimeException {
    public OptimisticLockException(Class<?> entityClass, Object id, Object expectedVersion) {
        super(String.format(
                "Optimistic lock failure: entity %s, id=%s, expected version=%s. The record was modified by another transaction.",
                entityClass.getSimpleName(), id, expectedVersion));
    }

    public OptimisticLockException(String message) {
        super(message);
    }
}

