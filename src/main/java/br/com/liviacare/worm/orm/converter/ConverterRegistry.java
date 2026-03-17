package br.com.liviacare.worm.orm.converter;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ConverterRegistry {

    private final Map<Class<?>, TypedColumnConverter<?, ?>> converters = new HashMap<>();

    public ConverterRegistry(List<TypedColumnConverter<?, ?>> list) {
        if (list != null) {
            for (TypedColumnConverter<?, ?> c : list) {
                converters.put(c.javaType(), c);
            }
        }
        // register built-in converters for common types here if needed
    }

    public boolean hasConverter(Class<?> javaType) {
        return converters.containsKey(javaType) || javaType.isEnum();
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    public Object toDatabase(Object value, Class<?> javaType) {
        if (value == null) return null;
        TypedColumnConverter conv = converters.get(javaType);
        if (conv != null) return conv.toDatabase(value);
        if (javaType.isEnum()) return value.toString();
        return value;
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    public Object fromDatabase(Object rawValue, Class<?> javaType) {
        if (rawValue == null) return null;
        TypedColumnConverter conv = converters.get(javaType);
        if (conv != null) return conv.fromDatabase(rawValue);
        if (javaType.isEnum()) {
            try {
                return Enum.valueOf((Class) javaType, rawValue.toString());
            } catch (Exception e) {
                return null;
            }
        }
        return rawValue;
    }
}

