package br.com.liviacare.worm.query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Fluent builder for SQL WHERE / ORDER BY / GROUP BY fragments.
 * Not thread-safe; create one per request.
 */
public final class FilterBuilder {

    private final StringBuilder whereClause = new StringBuilder();
    private final List<Object> parameters = new ArrayList<>();
    private final List<String> orderByClauses = new ArrayList<>();
    private final List<String> groupByClauses = new ArrayList<>();
    private boolean ignoreSoftDelete = false;
    private boolean noJoin = false;
    private final List<Join> joins = new ArrayList<>();
    private boolean mainTableAliasRequested = false;
    private String mainTableAliasName = null;
    private final List<Cte> ctes = new ArrayList<>();
    private final List<WindowFunction> windowFunctions = new ArrayList<>();

    /** Static factory — less verbose than {@code new FilterBuilder()}. */
    public static FilterBuilder where() { return new FilterBuilder(); }

    public enum JoinType {
        INNER, LEFT, RIGHT, FULL, CROSS;
        @Override
        public String toString() { return this.name(); }
    }

    public record Join(JoinType type, String table, String alias, String on) {

        @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof Join j)) return false;
                return this.type == j.type && this.table.equalsIgnoreCase(j.table)
                        && this.alias.equals(j.alias) && this.on.equalsIgnoreCase(j.on);
            }

            @Override
            public int hashCode() {
                int r = type == null ? 0 : type.hashCode();
                r = 31 * r + (table == null ? 0 : table.toLowerCase().hashCode());
                r = 31 * r + (alias == null ? 0 : alias.hashCode());
                r = 31 * r + (on == null ? 0 : on.toLowerCase().hashCode());
                return r;
            }
        }

    /**
     * Add a join with explicit type.
     * Example: join(JoinType.LEFT, "professional_preferences", "p", "p.professional_id = a.professional_id")
     */
    public FilterBuilder join(JoinType type, String table, String alias, String on) {
        if (table == null || table.isBlank() || on == null || on.isBlank()) return this;
        String useAlias = alias == null || alias.isBlank() ? table.replace('.', '_') : alias;
        Join newJoin = new Join(type == null ? JoinType.INNER : type, table, useAlias, on);
        for (Join j : joins) {
            if (j.alias().equals(useAlias) && !j.equals(newJoin)) {
                throw new IllegalArgumentException("Alias '" + useAlias + "' is already used for a different join");
            }
        }
        if (!joins.contains(newJoin)) {
            joins.add(newJoin);
        }
        this.mainTableAliasRequested = true;

        return this;
    }

    /**
     * Shortcut for INNER JOIN
     */
    public FilterBuilder join(String table, String alias, String on) {
        return join(JoinType.INNER, table, alias, on);
    }

    public FilterBuilder leftJoin(String table, String alias, String on) {
        return join(JoinType.LEFT, table, alias, on);
    }

    public FilterBuilder rightJoin(String table, String alias, String on) {
        return join(JoinType.RIGHT, table, alias, on);
    }

    public FilterBuilder crossJoin(String table, String alias, String on) {
        return join(JoinType.CROSS, table, alias, on);
    }

    /**
     * Suppresses ALL joins in the generated query, including those defined via
     * {@code @DbJoin} annotations on the entity and any joins added explicitly
     * through {@link #join}, {@link #leftJoin}, etc.
     * <p>
     * Useful when you only need the main table and want to avoid the overhead
     * of loading related entities.
     *
     * @return this builder (fluent API)
     */
    public FilterBuilder notJoin() {
        this.noJoin = true;
        return this;
    }

    /**
     * Build the JOIN clauses as a string (leading space included when non-empty).
     */
    public String buildJoins() {
        if (joins.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Join j : joins) {
            sb.append(' ')
              .append(j.type().toString())
              .append(" JOIN ")
              .append(j.table())
              .append(' ')
              .append(j.alias())
              .append(" ON ")
              .append(j.on());
        }
        return sb.toString();
    }

    // ---------------------- predicate helpers -------------------------------

    public FilterBuilder eq(String column, Object value) {
        and();
        whereClause.append(column).append(" = ?");
        parameters.add(convertParam(value));
        return this;
    }

    public FilterBuilder eqIfNotNull(String column, Object value) {
        return value != null ? eq(column, value) : this;
    }

    public FilterBuilder neq(String column, Object value) {
        and();
        whereClause.append(column).append(" <> ?");
        parameters.add(convertParam(value));
        return this;
    }

    public FilterBuilder neqIfNotNull(String column, Object value) {
        return value != null ? neq(column, value) : this;
    }

    public FilterBuilder gt(String column, Object value) {
        and();
        whereClause.append(column).append(" > ?");
        parameters.add(convertParam(value));
        return this;
    }

    public FilterBuilder gtIfNotNull(String column, Object value) {
        return value != null ? gt(column, value) : this;
    }

    public FilterBuilder lt(String column, Object value) {
        and();
        whereClause.append(column).append(" < ?");
        parameters.add(convertParam(value));
        return this;
    }

    public FilterBuilder ltIfNotNull(String column, Object value) {
        return value != null ? lt(column, value) : this;
    }

    public FilterBuilder gte(String column, Object value) {
        and();
        whereClause.append(column).append(" >= ?");
        parameters.add(convertParam(value));
        return this;
    }

    public FilterBuilder gteIfNotNull(String column, Object value) {
        return value != null ? gte(column, value) : this;
    }

    public FilterBuilder lte(String column, Object value) {
        and();
        whereClause.append(column).append(" <= ?");
        parameters.add(convertParam(value));
        return this;
    }

    public FilterBuilder lteIfNotNull(String column, Object value) {
        return value != null ? lte(column, value) : this;
    }

    public FilterBuilder like(String column, String value) {
        and();
        whereClause.append(column).append(" LIKE ?");
        parameters.add(value);
        return this;
    }

    public FilterBuilder likeIfNotBlank(String column, String value) {
        return (value != null && !value.isBlank()) ? like(column, value) : this;
    }

    public FilterBuilder in(String column, Collection<?> values) {
        if (values == null || values.isEmpty()) {
            and();
            whereClause.append("1=0");
            return this;
        }
        and();
        whereClause.append(column).append(" IN (");
        int i = 0;
        for (Object v : values) {
            if (i++ > 0) whereClause.append(',');
            whereClause.append('?');
            parameters.add(convertParam(v));
        }
        whereClause.append(')');
        return this;
    }

    public FilterBuilder inIfNotEmpty(String column, Collection<?> values) {
        return (values != null && !values.isEmpty()) ? in(column, values) : this;
    }

    public FilterBuilder isNull(String column) {
        and();
        whereClause.append(column).append(" IS NULL");
        return this;
    }

    public FilterBuilder isNotNull(String column) {
        and();
        whereClause.append(column).append(" IS NOT NULL");
        return this;
    }

    // ---------------------- JSONB helpers ---------------------------------

    /**
     * Compare a JSONB text value extracted by key: column->>'key' = value
     * Example: jsonTextEq("address", "state", "SP") -> address->>'state' = ?
     */
    public FilterBuilder jsonTextEq(String column, String key, Object value) {
        if (column == null || column.isBlank() || key == null || key.isBlank()) return this;
        and();
        whereClause.append(column).append("->>'").append(key).append("' = ?");
        parameters.add(convertParam(value));
        return this;
    }

    public FilterBuilder jsonTextEqIfNotNull(String column, String key, Object value) {
        return value != null ? jsonTextEq(column, key, value) : this;
    }

    /**
     * Compare a nested JSONB text path. Pass path parts in order.
     * Example: jsonTextEqPath("data", new String[]{"address","state"}, "SP") -> data->'address'->>'state' = ?
     */
    public FilterBuilder jsonTextEqPath(String column, String[] pathParts, Object value) {
        if (column == null || column.isBlank() || pathParts == null || pathParts.length == 0) return this;
        for (String p : pathParts) if (p == null || p.isBlank()) return this;
        and();
        whereClause.append(column);
        for (int i = 0; i < pathParts.length - 1; i++) {
            whereClause.append("->'").append(pathParts[i]).append("'");
        }
        if (pathParts.length >= 1) {
            whereClause.append("->>'").append(pathParts[pathParts.length - 1]).append("'");
        }
        whereClause.append(" = ?");
        parameters.add(convertParam(value));
        return this;
    }

    public FilterBuilder jsonTextEqPathIfNotNull(String column, String[] pathParts, Object value) {
        return value != null ? jsonTextEqPath(column, pathParts, value) : this;
    }

    /**
     * Check if JSONB column has the top-level key. Uses Postgres function jsonb_exists(jsonb, text)
     */
    public FilterBuilder jsonHasKey(String column, String key) {
        if (column == null || column.isBlank() || key == null || key.isBlank()) return this;
        and();
        whereClause.append("jsonb_exists(").append(column).append(", ?)");
        parameters.add(key);
        return this;
    }

    /**
     * Check if JSONB column has any of the provided keys. Uses jsonb_exists_any(jsonb, text[])
     */
    public FilterBuilder jsonHasAny(String column, Collection<String> keys) {
        if (column == null || column.isBlank() || keys == null || keys.isEmpty()) return this;
        and();
        whereClause.append("jsonb_exists_any(").append(column).append(", ?)");
        parameters.add(keys.toArray(new String[0]));
        return this;
    }

    /**
     * jsonb contains: column @> jsonb
     * Caller should pass a JSON string (e.g. '{"state":"SP"}') or a value the JDBC driver can map.
     */
    public FilterBuilder jsonContains(String column, Object jsonValue) {
        if (column == null || column.isBlank() || jsonValue == null) return this;
        and();
        whereClause.append(column).append(" @> ?::jsonb");
        parameters.add(convertParam(jsonValue));
        return this;
    }

    public FilterBuilder jsonContainsIfNotNull(String column, Object jsonValue) {
        return jsonValue == null ? this : jsonContains(column, jsonValue);
    }

    // ---------------------- end JSONB helpers -----------------------------

    /**
     * JSONPath helpers (Postgres jsonb_path_exists/jsonb_path_query_first compatible)
     */
    public FilterBuilder jsonPathExists(String column, String jsonPath) {
        if (column == null || column.isBlank() || jsonPath == null || jsonPath.isBlank()) return this;
        and();
        whereClause.append("jsonb_path_exists(").append(column).append(", ?::jsonpath)");
        parameters.add(jsonPath);
        return this;
    }

    public FilterBuilder jsonPathExistsIfNotNull(String column, String jsonPath) {
        return (jsonPath != null && !jsonPath.isBlank()) ? jsonPathExists(column, jsonPath) : this;
    }

    /**
     * jsonb_path_exists with vars (varsJson must be a JSON string or object convertible to jsonb by the driver)
     * Calls: jsonb_path_exists(column, jsonPath::jsonpath, vars::jsonb)
     */
    public FilterBuilder jsonPathExistsWithVars(String column, String jsonPath, Object varsJson) {
        if (column == null || column.isBlank() || jsonPath == null || jsonPath.isBlank() || varsJson == null) return this;
        and();
        whereClause.append("jsonb_path_exists(").append(column).append(", ?::jsonpath, ?::jsonb)");
        parameters.add(jsonPath);
        parameters.add(convertParam(varsJson));
        return this;
    }

    public FilterBuilder jsonPathExistsWithVarsIfNotNull(String column, String jsonPath, Object varsJson) {
        return varsJson != null ? jsonPathExistsWithVars(column, jsonPath, varsJson) : this;
    }

    public FilterBuilder openParen() {
        and();
        whereClause.append('(');
        return this;
    }

    public FilterBuilder closeParen() {
        whereClause.append(')');
        return this;
    }

    public FilterBuilder or() {
        whereClause.append(" OR ");
        return this;
    }

    // ---------------------- ordering / grouping -----------------------------

    public FilterBuilder orderBy(String column) {
        return orderBy(column, true);
    }

    public FilterBuilder orderBy(Pageable.Sort sort) {
        if (sort == null) return this;
        boolean asc = sort.direction() == Pageable.Direction.ASC;
        return orderBy(sort.property(), asc);
    }

    public FilterBuilder orderByDesc(String column) {
        return orderBy(column, false);
    }

    public FilterBuilder orderBy(String column, boolean ascending) {
        if (column == null || column.isBlank()) return this;
        orderByClauses.add(column + (ascending ? " ASC" : " DESC"));
        return this;
    }

    public FilterBuilder orderByRaw(String clause) {
        if (clause == null || clause.isBlank()) return this;
        orderByClauses.add(clause.trim());
        return this;
    }

    public FilterBuilder groupBy(String... column) {
        if (column == null) return this;
        for (String col : column) {
            if (col == null || col.isBlank()) continue;
            groupByClauses.add(col.trim());
        }
        return this;
    }

    public FilterBuilder groupByRaw(String clause) {
        if (clause == null || clause.isBlank()) return this;
        groupByClauses.add(clause.trim());
        return this;
    }

    // ---------------------- internal helpers -------------------------------

    private void and() {
        final int len = whereClause.length();
        if (len == 0) return;
        if (whereClause.charAt(len - 1) == '(') return;
        if (len >= 4
                && whereClause.charAt(len - 4) == ' '
                && whereClause.charAt(len - 3) == 'O'
                && whereClause.charAt(len - 2) == 'R'
                && whereClause.charAt(len - 1) == ' ') return;
        whereClause.append(" AND ");
    }

    private Object convertParam(Object val) {
        if (val instanceof Enum<?> e) return e.name();
        return val;
    }

    // ---------------------- build / accessors ------------------------------

    public String build() {
        return whereClause.toString();
    }

    public String buildOrderBy() {
        if (orderByClauses.isEmpty()) return "";
        return " ORDER BY " + String.join(", ", orderByClauses);
    }

    public boolean hasOrderBy() {
        return !orderByClauses.isEmpty();
    }

    public String buildGroupBy() {
        if (groupByClauses.isEmpty()) return "";
        return " GROUP BY " + String.join(", ", groupByClauses);
    }

    public List<Object> getParameters() {
        return parameters;
    }

    // ---------------------- compatibility with previous API ----------------

    public String getWhereClause() {
        return build();
    }

    public List<Object> getArgs() {
        return getParameters();
    }

    public FilterBuilder ignoreSoftDelete() {
        this.ignoreSoftDelete = true;
        return this;
    }

    public boolean isIgnoreSoftDelete() { return this.ignoreSoftDelete; }

    public boolean isNoJoin() { return this.noJoin; }

    public List<Join> getJoins() { return this.joins; }

    public FilterBuilder mainAlias(String alias) {
        if (alias != null && !alias.isBlank()) {
            String newAlias = alias.trim();
            String oldAlias = this.mainTableAliasName == null ? "a" : this.mainTableAliasName;
            this.mainTableAliasRequested = true;
            this.mainTableAliasName = newAlias;
            replaceMainAliasOccurrences(oldAlias, newAlias);
        }
        return this;
    }

    /**
     * Convenience alias for {@link #mainAlias(String)}.
     *
     * <p>Sets the alias for the main table entity, which will be automatically applied
     * to all references of the main table's columns throughout the query.
     *
     * <p><strong>Usage example:</strong>
     * <pre>
     *   FilterBuilder filter = new FilterBuilder()
     *       .alias("u")
     *       .eq("u.name", "John")
     *       .orderBy("u.createdAt")
     *       .groupBy("u.status");
     * </pre>
     *
     * <p><strong>How the alias is applied:</strong>
     * <ul>
     *   <li><strong>SELECT clause:</strong> Column references in the SELECT list will be qualified
     *       with the alias (e.g., "u.id, u.name, u.email")</li>
     *   <li><strong>FROM clause:</strong> The main table name will be suffixed with the alias
     *       (e.g., "FROM users u")</li>
     *   <li><strong>WHERE clause:</strong> Bare column references will be automatically qualified
     *       with the alias (e.g., "u.name = ?" for filter.eq("name", "John"))</li>
     *   <li><strong>ORDER BY clause:</strong> Column references will be qualified with the alias
     *       (e.g., "ORDER BY u.createdAt ASC")</li>
     *   <li><strong>GROUP BY clause:</strong> Column references will be qualified with the alias
     *       (e.g., "GROUP BY u.status")</li>
     *   <li><strong>JOIN ON clauses:</strong> References to the main table will be updated
     *       (e.g., "ON profile.user_id = u.id")</li>
     * </ul>
     *
     * <p><strong>Notes:</strong>
     * <ul>
     *   <li>If joins are present in the filter, an alias is automatically requested
     *       (defaults to "a" if not explicitly set)</li>
     *   <li>This method is idempotent when called multiple times with the same alias</li>
     *   <li>Column references in property mapping are automatically qualified when
     *       using bare property names in methods like orderBy("propertyName")</li>
     * </ul>
     *
     * @param alias the alias to use for the main table (e.g., "u", "user", "a")
     * @return this FilterBuilder for method chaining
     */
    public FilterBuilder alias(String alias) {
        return mainAlias(alias);
    }

    private void replaceMainAliasOccurrences(String fromAlias, String toAlias) {
        if (fromAlias == null || fromAlias.isBlank() || toAlias == null || toAlias.isBlank()) return;
        String toDot = toAlias + ".";
        Pattern p = Pattern.compile("\\b" + Pattern.quote(fromAlias) + "\\.", Pattern.CASE_INSENSITIVE);
        String where = whereClause.toString();
        if (!where.isEmpty()) {
            String replaced = p.matcher(where).replaceAll(toDot);
            whereClause.setLength(0);
            whereClause.append(replaced);
        }
        for (int i = 0; i < joins.size(); i++) {
            Join j = joins.get(i);
            String on = j.on();
            String newOn = on == null ? null : p.matcher(on).replaceAll(toDot);
            if (newOn != null && !newOn.equals(on)) {
                joins.set(i, new Join(j.type(), j.table(), j.alias(), newOn));
            }
        }
    }

    public String getMainTableAlias() { return mainTableAliasName; }

    public String buildFromClause(String mainTable, List<Join> joins) {
        String tableWithAlias = mainTable;
        if (!mainTable.matches(".*\\s+AS\\s+.*") && !mainTable.matches(".*\\s+.*")) {
            if (this.mainTableAliasRequested) {
                String a = (this.mainTableAliasName != null && !this.mainTableAliasName.isBlank()) ? this.mainTableAliasName : "a";
                tableWithAlias = mainTable + " " + a;
            }
        }
        StringBuilder from = new StringBuilder("FROM ").append(tableWithAlias);
        if (joins != null) {
            for (Join j : joins) {
                from.append(' ').append(j.type()).append(" JOIN ")
                    .append(j.table()).append(' ').append(j.alias())
                    .append(" ON ").append(j.on());
            }
        }
        return from.toString();
    }

    /** @param sql raw SQL for the CTE; {@code subQuery} is optional */
    public record Cte(String name, String sql, FilterBuilder subQuery) {}

    public record WindowFunction(String expression, String alias) {}

    public FilterBuilder withCte(String name, String rawSql) {
        if (name == null || name.isBlank() || rawSql == null || rawSql.isBlank()) return this;
        ctes.add(new Cte(name, rawSql, null));
        return this;
    }

    public FilterBuilder withCte(String name, FilterBuilder subQuery) {
        if (name == null || name.isBlank() || subQuery == null) return this;
        ctes.add(new Cte(name, null, subQuery));
        return this;
    }

    public FilterBuilder addWindowFunction(String expression, String alias) {
        if (expression == null || expression.isBlank() || alias == null || alias.isBlank()) return this;
        windowFunctions.add(new WindowFunction(expression, alias));
        return this;
    }

    public List<Cte> getCtes() { return java.util.Collections.unmodifiableList(ctes); }
    public List<WindowFunction> getWindowFunctions() { return java.util.Collections.unmodifiableList(windowFunctions); }

    public boolean isEmpty() {
        return whereClause.isEmpty() &&
               parameters.isEmpty() &&
               orderByClauses.isEmpty() &&
               groupByClauses.isEmpty() &&
               joins.isEmpty() &&
               ctes.isEmpty() &&
               windowFunctions.isEmpty();
    }
}
