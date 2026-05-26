package com.github.rfdetoni.worm.util;

import com.github.rfdetoni.worm.annotation.mapping.DbTable;

import java.util.Set;

/**
 * Shared alias utilities for deterministic and collision-free SQL alias generation.
 */
public final class AliasUtils {

    private AliasUtils() {
    }

    /**
     * Deprecated. Prefer the string-based overloads that accept a table name.
     * This wrapper is kept for binary compatibility and delegates to
     * {@link #defaultMainAlias(String)} after resolving a table name from
     * {@link #entityTableName(Class)}.
     */
    @Deprecated
    public static String defaultMainAlias(Class<?> entityClass) {
        if (entityClass == null) return "entity";
        return defaultMainAlias(entityTableName(entityClass));
    }

    // Cache of normalized table/name -> alias to avoid repeated allocations and
    // reflection hotspots in hot paths (query building / metadata creation).
    private static final java.util.concurrent.ConcurrentHashMap<String, String> ALIAS_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public static String defaultMainAlias(String tableOrName) {
        String base = normalizeBaseName(tableOrName);
        if (base.isBlank()) return "entity";
        return ALIAS_CACHE.computeIfAbsent(base, k -> {
            String sanitized = sanitizeAlias(k);
            return sanitized.isBlank() ? "entity" : sanitized;
        });
    }

    /**
     * Helper to resolve an entity class to its DB table name.
     * If the class carries {@link DbTable} with a non-blank value, that value is returned,
     * otherwise the simple class name lowercased is returned.
     */
    public static String entityTableName(Class<?> entityClass) {
        if (entityClass == null) return "";
        DbTable ann = entityClass.getAnnotation(DbTable.class);
        if (ann != null && ann.value() != null && !ann.value().isBlank()) return ann.value();
        return entityClass.getSimpleName().toLowerCase();
    }

    public static String defaultJoinAlias(String relationName, String tableOrName) {
        // Join aliases are derived from the join table only. Relation-based aliases
        // are no longer used to avoid coupling SQL aliasing to Java names.
        return defaultMainAlias(tableOrName);
    }

    /**
     * Convenience overload: derive a join alias directly from the join table name.
     */
    public static String defaultJoinAlias(String tableName) {
        return defaultMainAlias(tableName);
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
        // Fast manual normalization: keep letters/digits, convert separators to '_', then
        // convert snake/sep style to camelCase-like alias (lower_first, remove separators).
        StringBuilder sb = new StringBuilder(a.length());
        boolean upperNext = false;
        for (int i = 0; i < a.length(); i++) {
            char c = a.charAt(i);
            if (c == '.' || c == '-' || c == ' ' || c == '_') {
                upperNext = sb.length() > 0; // only uppercase if we already have a char
                continue;
            }
            if (Character.isLetterOrDigit(c)) {
                if (upperNext) {
                    sb.append(Character.toUpperCase(c));
                    upperNext = false;
                } else {
                    sb.append(Character.toLowerCase(c));
                }
            }
        }
        if (sb.length() == 0) return "";
        sb.setCharAt(0, Character.toLowerCase(sb.charAt(0)));
        return sb.toString();
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
        // Fallback: reuse sanitizeAlias behavior to produce a camel-like form
        return sanitizeAlias(value);
    }

    private static String decapitalize(String value) {
        if (value == null || value.isBlank()) return "";
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }
}

