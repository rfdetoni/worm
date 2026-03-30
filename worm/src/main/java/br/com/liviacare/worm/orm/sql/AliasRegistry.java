package br.com.liviacare.worm.orm.sql;

import br.com.liviacare.worm.annotation.mapping.DbTable;
import br.com.liviacare.worm.util.AliasUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Registry that assigns deterministic aliases for entity classes and tracks
 * already used aliases within a query build scope to avoid collisions.
 */
public final class AliasRegistry {

    // key: class FQCN
    private final Map<String, String> classToAlias = new LinkedHashMap<>();
    private final Set<String> usedAliases = new LinkedHashSet<>();

    /**
     * Register and return the alias for a given class. Idempotent.
     */
    public String register(Class<?> entityClass) {
        String key = (entityClass == null) ? "<null>" : entityClass.getName();
        if (classToAlias.containsKey(key)) return classToAlias.get(key);

        // Prefer @DbTable value if present to derive a stable alias from the logical table name
        String baseName = null;
        if (entityClass != null) {
            DbTable ann = entityClass.getAnnotation(DbTable.class);
            if (ann != null && ann.value() != null && !ann.value().isBlank()) baseName = ann.value();
        }
        if (baseName == null && entityClass != null) baseName = entityClass.getSimpleName();
        if (baseName == null) baseName = "entity";

        String baseAlias = AliasUtils.defaultMainAlias(baseName);
        String alias = baseAlias;
        int counter = 2;
        while (usedAliases.contains(alias.toLowerCase())) alias = baseAlias + counter++;

        classToAlias.put(key, alias);
        usedAliases.add(alias.toLowerCase());
        return alias;
    }

    /**
     * Register a provided alias for the class (explicit alias requested by caller).
     * If the class was already registered, the existing alias is returned.
     */
    public String registerWithAlias(Class<?> entityClass, String alias) {
        if (alias == null || alias.isBlank()) return alias;
        String key = (entityClass == null) ? "<null>" : entityClass.getName();
        if (classToAlias.containsKey(key)) return classToAlias.get(key);
        String sanitized = alias.trim();
        String candidate = sanitized;
        int counter = 2;
        while (usedAliases.contains(candidate.toLowerCase())) candidate = sanitized + counter++;
        classToAlias.put(key, candidate);
        usedAliases.add(candidate.toLowerCase());
        return candidate;
    }

    /**
     * Mark an alias as already used in the current build scope (e.g. user-supplied join aliases).
     */
    public void registerUsedAlias(String alias) {
        if (alias == null || alias.isBlank()) return;
        usedAliases.add(alias.trim().toLowerCase());
    }

    /**
     * Return the alias registered for the class or throw if none.
     */
    public String get(Class<?> entityClass) {
        String key = (entityClass == null) ? "<null>" : entityClass.getName();
        String alias = classToAlias.get(key);
        if (alias == null) throw new IllegalStateException("Alias not registered for: " + key);
        return alias;
    }

    public boolean isRegistered(Class<?> entityClass) {
        String key = (entityClass == null) ? "<null>" : entityClass.getName();
        return classToAlias.containsKey(key);
    }
}

