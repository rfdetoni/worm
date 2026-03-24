package br.com.liviacare.worm.orm.converter;

/**
 * Optional user-provided converter interface.
 */
public interface TypedColumnConverter<J, D> {
    Class<J> javaType();
    D toDatabase(J value);
    J fromDatabase(Object rawJdbcValue);
}

