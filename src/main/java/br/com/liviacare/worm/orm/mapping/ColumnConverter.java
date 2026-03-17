package br.com.liviacare.worm.orm.mapping;

import java.sql.SQLException;

/**
 * A functional interface for converting a raw JDBC object value to the
 * appropriate entity field type. Implementations are determined once at startup
 * and cached within EntityMetadata to ensure maximum mapping performance.
 */
@FunctionalInterface
public interface ColumnConverter {
    /**
     * Converts the raw object from the ResultSet.
     * @param raw The raw object from {@code ResultSet.getObject()}.
     * @return The converted object, ready to be set on the entity.
     * @throws SQLException if a conversion error occurs.
     */
    Object convert(Object raw) throws SQLException;
}
