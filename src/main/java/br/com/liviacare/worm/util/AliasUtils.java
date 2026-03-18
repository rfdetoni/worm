package br.com.liviacare.worm.util;

import java.util.Set;

/**
 * Shared alias utilities for deterministic and collision-free SQL alias generation.
 */
public final class AliasUtils {

    private AliasUtils() {
    }

    public static String defaultMainAlias(Class<?> entityClass) {
        if (entityClass == null || entityClass.getSimpleName().isBlank()) return "entity";
        return decapitalize(entityClass.getSimpleName());
    }

    public static String defaultMainAlias(String tableOrName) {
        String base = normalizeBaseName(tableOrName);
        String camel = toCamelCase(base);
        return camel.isBlank() ? "entity" : camel;
    }

    public static String defaultJoinAlias(String relationName, String tableOrName) {
        if (relationName != null && !relationName.isBlank()) {
            String candidate = toCamelCase(relationName.trim());
            if (!candidate.isBlank()) return candidate;
        }
        return defaultMainAlias(tableOrName);
    }

    public static String ensureUniqueAlias(String baseAlias, Set<String> usedAliasesLowerCase) {
        String base = sanitizeAlias(baseAlias);
        if (base.isBlank()) base = "join";

        String candidate = base;
        int suffix = 2;
        while (usedAliasesLowerCase.contains(candidate.toLowerCase())) {
            candidate = base + suffix;
            suffix++;
        }
        usedAliasesLowerCase.add(candidate.toLowerCase());
        return candidate;
    }

    public static String sanitizeAlias(String alias) {
        if (alias == null) return "";
        String a = alias.trim();
        if (a.isEmpty()) return "";
        a = a.replace('.', '_').replace('-', '_').replace(' ', '_');
        return toCamelCase(a);
    }

    private static String normalizeBaseName(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return "";
        int dot = trimmed.lastIndexOf('.');
        return dot >= 0 ? trimmed.substring(dot + 1) : trimmed;
    }

    private static String toCamelCase(String value) {
        if (value == null || value.isBlank()) return "";
        String[] parts = value.split("[_\\-\\s]+");
        if (parts.length == 0) return "";

        StringBuilder out = new StringBuilder(parts[0].toLowerCase());
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].isBlank()) continue;
            String p = parts[i].toLowerCase();
            out.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return out.toString();
    }

    private static String decapitalize(String value) {
        if (value == null || value.isBlank()) return "";
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }
}

