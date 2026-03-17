package br.com.liviacare.worm.orm.exception;

/**
 * Custom exception for errors that occur during ORM operations.
 * <p>
 * This unchecked exception is used to wrap underlying exceptions (like
 * {@link ReflectiveOperationException} or {@code SQLException}) that can occur
 * during entity persistence, retrieval, or other database interactions.
 */
public class OrmOperationException extends RuntimeException {

    /**
     * Constructs a new ORM operation exception with the specified detail message.
     *
     * @param message The detail message.
     */
    public OrmOperationException(String message) {
        super(message);
    }

    /**
     * Constructs a new ORM operation exception with the specified detail message and cause.
     *
     * @param message The detail message.
     * @param cause   The cause of the exception.
     */
    public OrmOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
