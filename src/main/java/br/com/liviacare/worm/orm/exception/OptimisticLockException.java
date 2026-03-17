package br.com.liviacare.worm.orm.exception;

public class OptimisticLockException extends RuntimeException {
    public OptimisticLockException(Class<?> entityClass, Object id, Object expectedVersion) {
        super(String.format(
                "Falha de concorrência: entidade %s, id=%s, versão esperada=%s. O registro foi modificado por outra transação.",
                entityClass.getSimpleName(), id, expectedVersion));
    }

    public OptimisticLockException(String message) {
        super(message);
    }
}

