package br.com.liviacare.worm.orm.registry;

import br.com.liviacare.worm.orm.mapping.ColumnConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.postgresql.util.PGobject;

import java.lang.reflect.Type;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

final class ConverterFactory {
    private static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @SuppressWarnings({"unchecked", "rawtypes"})
    static ColumnConverter getConverter(Class<?> target, Type genericType) {
        if (target == LocalDate.class)     return localDateConverter();
        if (target == LocalDateTime.class) return localDateTimeConverter();
        if (target == Instant.class)       return instantConverter();
        if (target.isEnum())               return enumConverter(target);
        if (isJsonCandidate(target))       return jsonConverter(target, genericType);
        return raw -> raw;
    }

    private static boolean isJsonCandidate(Class<?> t) {
        return List.class.isAssignableFrom(t)
                || Map.class.isAssignableFrom(t)
                || (!t.isPrimitive() && !t.isEnum()
                && !t.getName().startsWith("java.")
                && !t.getName().startsWith("javax."));
    }

    private static ColumnConverter localDateConverter() {
        return raw -> {
            if (raw == null) return null;
            return switch (raw) {
                case Date d       -> d.toLocalDate();
                case Timestamp ts -> ts.toLocalDateTime().toLocalDate();
                default           -> raw;
            };
        };
    }

    private static ColumnConverter localDateTimeConverter() {
        return raw -> {
            if (raw == null) return null;
            return switch (raw) {
                case Timestamp ts -> ts.toLocalDateTime();
                case Date d       -> d.toLocalDate().atStartOfDay();
                default           -> raw;
            };
        };
    }

    private static ColumnConverter instantConverter() {
        return raw -> {
            if (raw == null) return null;
            return switch (raw) {
                case Timestamp ts -> ts.toInstant();
                case Date d       -> d.toInstant();
                default           -> raw;
            };
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ColumnConverter enumConverter(Class target) {
        return raw -> {
            if (raw == null) return null;
            if (target.isInstance(raw)) return raw;
            String s = raw.toString();
            try {
                return Enum.valueOf(target, s);
            } catch (IllegalArgumentException ignored) {
                for (Object c : target.getEnumConstants())
                    if (((Enum<?>) c).name().equalsIgnoreCase(s)) return c;
                if (raw instanceof Number n) {
                    int idx = n.intValue();
                    Object[] consts = target.getEnumConstants();
                    if (idx >= 0 && idx < consts.length) return consts[idx];
                }
                return raw;
            }
        };
    }

    private static ColumnConverter jsonConverter(Class<?> target, Type genericType) {
        return raw -> {
            if (raw instanceof PGobject pg && ("json".equals(pg.getType()) || "jsonb".equals(pg.getType()))) {
                String value = pg.getValue();
                if (value == null) return null;
                try {
                    var typeRef = genericType != null
                            ? MAPPER.getTypeFactory().constructType(genericType)
                            : MAPPER.getTypeFactory().constructType(target);
                    return MAPPER.readValue(value, typeRef);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to deserialize JSON from DB for type " + target.getName(), e);
                }
            }
            return raw;
        };
    }
}
