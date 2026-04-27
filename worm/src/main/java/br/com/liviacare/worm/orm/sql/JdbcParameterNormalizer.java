package br.com.liviacare.worm.orm.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Normalizes Java values to JDBC-friendly scalar representations.
 */
public final class JdbcParameterNormalizer {

    private JdbcParameterNormalizer() {
    }

    public static Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid.toString();
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        return value;
    }

    public static List<Object> normalizeAll(List<?> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<Object> normalized = new ArrayList<>(values.size());
        for (Object value : values) {
            normalized.add(normalize(value));
        }
        return normalized;
    }
}
