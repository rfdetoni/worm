package com.github.rfdetoni.worm.orm.query;

import java.util.Locale;

public final class JoinClauseBuilder {

    private JoinClauseBuilder() {}

    /**
     * Infers an ON clause for a join based on naming conventions.
     * This is used by FilterBuilder.joinByRelation and as a fallback for @DbJoin.
     *
     * @param joinTable The name of the table being joined.
     * @param joinAlias The alias for the joined table.
     * @param relationName The name of the relationship (e.g., from a field name).
     * @param mainAlias The alias of the main table.
     * @param referencedColumn The column on the joined table (usually a PK, defaults to 'id').
     * @return The generated ON clause string.
     */
    public static String inferOnClause(
            String joinTable,
            String joinAlias,
            String relationName,
            String mainAlias,
            String referencedColumn
    ) {
        String inferredLocalColumn = toSnakeCase(relationName) + "_id";
        
        String relationPlural = pluralize(relationName);
        boolean isDirectRelation = joinTable.equalsIgnoreCase(relationName) || joinTable.equalsIgnoreCase(relationPlural);

        // Heuristic 1: For linking tables like 'professional_preferences', where the FK might be on both tables.
        // If the join table's name contains the relation name, assume the FK column name is the same on both.
        // e.g., join "professional_preferences" for relation "professional"
        // -> ON professional_preferences.professional_id = main_table.professional_id
        if (!isDirectRelation && joinTable.toLowerCase().contains(relationName.toLowerCase())) {
            return joinAlias + "." + inferredLocalColumn + " = " + mainAlias + "." + inferredLocalColumn;
        }

        // Heuristic 2: Default for many-to-one.
        // e.g., join "users" for relation "author"
        // -> ON users.id = main_table.author_id
        String refCol = (referencedColumn == null || referencedColumn.isBlank()) ? "id" : referencedColumn;
        return joinAlias + "." + refCol + " = " + mainAlias + "." + inferredLocalColumn;
    }

    public static String toSnakeCase(String value) {
        if (value == null || value.isBlank()) return "id";
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    public static String singularize(String value) {
        if (value == null || value.isBlank()) return "entity";
        if (value.endsWith("ies") && value.length() > 3) return value.substring(0, value.length() - 3) + "y";
        if (value.endsWith("s") && value.length() > 1) return value.substring(0, value.length() - 1);
        return value;
    }

    public static String pluralize(String value) {
        if (value == null || value.isBlank()) return "";
        if (value.endsWith("y")) return value.substring(0, value.length() - 1) + "ies";
        if (value.endsWith("s")) return value;
        return value + "s";
    }
}
