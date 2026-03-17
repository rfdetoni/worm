package br.com.liviacare.worm.orm.sql;

import br.com.liviacare.worm.annotation.mapping.OrderBy;
import br.com.liviacare.worm.orm.dialect.SqlDialect;
import br.com.liviacare.worm.orm.registry.EntityMetadata;
import br.com.liviacare.worm.orm.registry.JoinInfo;
import br.com.liviacare.worm.orm.registry.ProjectionMetadata;
import br.com.liviacare.worm.query.FilterBuilder;
import br.com.liviacare.worm.query.Pageable;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds safe, parameterized native SQL queries for entities.
 *
 * <p>Encapsulates join resolution, soft-delete filtering, ORDER BY resolution,
 * and dialect-aware pagination — without JPA/Hibernate.
 *
 * <p>All public {@code build*} methods return SQL strings where bind parameters
 * are expressed as positional {@code ?} placeholders; bound values are
 * accessible via {@link #getParameters()}.
 *
 * <p><strong>Thread safety:</strong> instances are NOT thread-safe; create one
 * per query invocation.
 *
 * @param <T> the entity type
 */
public final class QueryBuilder<T> {

    // -------------------------------------------------------------------------
    // SQL keyword fragments — centralised for consistency
    // -------------------------------------------------------------------------
    private static final String WHERE   = " WHERE ";
    private static final String AND     = " AND ";
    private static final String ORDER_BY = " ORDER BY ";

    // SQL injection guard: only alphanumeric, underscore, dot and basic operators
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[\\w.]+");

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------
    private final EntityMetadata<T> metadata;
    private final FilterBuilder     filter;
    private final SqlDialect        dialect;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Creates a new {@code QueryBuilder}.
     *
     * @param metadata non-null entity metadata
     * @param filter   optional filter; a no-op {@link FilterBuilder} is used when null
     * @param dialect  optional SQL dialect for pagination; standard LIMIT/OFFSET is used when null
     */
    public QueryBuilder(EntityMetadata<T> metadata, FilterBuilder filter, SqlDialect dialect) {
        this.metadata = Objects.requireNonNull(metadata, "metadata cannot be null");
        this.filter   = (filter != null) ? filter : new FilterBuilder();
        this.dialect  = dialect;
    }

    // =========================================================================
    // Public API — SELECT
    // =========================================================================

    /**
     * Builds a SELECT query for the entity, optionally paginated.
     *
     * @param pageable     pagination/sort descriptor; may be null
     * @param fetchOneMore when {@code true}, fetches {@code pageSize + 1} rows
     *                     (cursor-style "has next page" detection)
     * @return complete SQL string
     */
    public String buildSelectSql(Pageable pageable, boolean fetchOneMore) {
        AliasContext ctx = buildAliasContext();
        String baseSql  = applyCtesAndWindowFunctions(metadata.selectSql());
        baseSql = normaliseMainTableAlias(baseSql, ctx);
        if (filter.isNoJoin()) baseSql = stripJoinsFromSql(baseSql);

        StringBuilder sql = new StringBuilder(baseSql);
        // Metadata joins are already baked into metadata.selectSql(); only append
        // filter-level joins here to avoid duplicating the ON condition.
        // If noJoin was requested, skip all joins entirely.
        if (!filter.isNoJoin()) {
            appendFilterJoins(sql, ctx);
        }
        appendWhere(sql, ctx);
        appendGroupBy(sql, ctx);
        appendOrderBy(sql, pageable, ctx);
        if (pageable != null) appendLimitOffset(sql, pageable, fetchOneMore);
        return sql.toString();
    }

    /**
     * Builds a SELECT query for a projection.
     *
     * @param projMeta     non-null projection metadata
     * @param pageable     pagination/sort descriptor; may be null
     * @param fetchOneMore cursor-style fetch-one-more flag
     * @return complete SQL string
     */
    public String buildSelectSql(ProjectionMetadata projMeta, Pageable pageable, boolean fetchOneMore) {
        Objects.requireNonNull(projMeta, "projMeta cannot be null");
        String baseSql = applyCtesAndWindowFunctions(projMeta.selectSql());

        StringBuilder sql = new StringBuilder(baseSql);
        // Projection selectSql already embeds the required joins via buildProjectionJoins.
        // Only add filter-level joins to avoid duplicating ON conditions.
        if (!filter.isNoJoin()) {
            appendFilterJoins(sql, AliasContext.NONE);
        }
        appendWhere(sql, AliasContext.NONE);
        appendOrderBy(sql, pageable, AliasContext.NONE);
        if (pageable != null) appendLimitOffset(sql, pageable, fetchOneMore);
        return sql.toString();
    }

    // =========================================================================
    // Public API — COUNT / EXISTS
    // =========================================================================

    /**
     * Builds a {@code SELECT COUNT(*)} query for the entity.
     *
     * @return complete SQL string
     */
    public String buildCountSql() {
        return "SELECT COUNT(*)" + buildFromJoinsAndWhere();
    }

    /**
     * Builds a {@code SELECT 1 … LIMIT 1} existence-check query.
     *
     * @return complete SQL string
     */
    public String buildExistsSql() {
        String sql = "SELECT 1" + buildFromJoinsAndWhere();
        return (dialect != null)
                ? dialect.applyPagination(sql, 1, 0)
                : sql + " LIMIT 1";
    }

    /**
     * Builds and returns the {@code FROM … JOIN … WHERE} fragment.
     *
     * <p>This fragment is reused by {@link #buildCountSql()} and
     * {@link #buildExistsSql()} and may also be called externally for
     * subquery composition.
     *
     * @return SQL fragment starting with {@code " FROM "}
     */
    public String buildFromJoinsAndWhere() {
        AliasContext ctx = buildAliasContext();

        StringBuilder sql = new StringBuilder();
        // When noJoin is active, pass an empty list so buildFromClause doesn't append any joins
        List<FilterBuilder.Join> joinsForFrom = filter.isNoJoin() ? Collections.emptyList() : filter.getJoins();
        sql.append(' ').append(filter.buildFromClause(metadata.tableName(), joinsForFrom));

        // Inject the main-table alias into the FROM fragment when needed
        if (ctx.hasAlias()) {
            injectAliasAfterTable(sql, metadata.tableName(), ctx.alias());
        }

        appendJoins(sql, ctx);
        appendWhere(sql, ctx);
        return sql.toString();
    }

    /**
     * Returns the ordered list of bind-parameter values corresponding to the
     * {@code ?} placeholders emitted in query strings.
     *
     * @return immutable view of bind parameters
     */
    public List<Object> getParameters() {
        return filter.getParameters();
    }

    // =========================================================================
    // Alias resolution
    // =========================================================================

    /**
     * Immutable value carrying the resolved alias state for one query build.
     */
    private record AliasContext(boolean hasAlias, String alias) {
        static final AliasContext NONE = new AliasContext(false, null);

        static AliasContext of(boolean hasAlias, String alias) {
            return hasAlias ? new AliasContext(true, alias) : NONE;
        }

        String qualifyOrNull(String column) {
            return hasAlias ? alias + "." + column : column;
        }
    }

    private AliasContext buildAliasContext() {
        String explicitAlias = blankToNull(filter.getMainTableAlias());
        boolean hasFilterJoins = filter.getJoins() != null && !filter.getJoins().isEmpty();
        // Also require an alias when the entity itself has @DbJoin metadata joins,
        // so that buildFromJoinsAndWhere (count/exists) correctly aliases the main
        // table for the ON clauses (e.g. "contact.patient_id = a.id").
        boolean hasMetaJoins = hasAnyValidMetadataJoin();
        boolean needsAlias   = explicitAlias != null || hasFilterJoins || hasMetaJoins;
        String resolved      = (explicitAlias != null) ? explicitAlias : "a";
        return AliasContext.of(needsAlias, resolved);
    }

    /** Returns true if the entity has at least one valid (non-null, non-blank) @DbJoin. */
    private boolean hasAnyValidMetadataJoin() {
        JoinInfo[] joins = metadata.joinInfos();
        if (joins == null) return false;
        for (JoinInfo ji : joins) {
            if (isValidJoin(ji)) return true;
        }
        return false;
    }

    /**
     * Rewrites occurrences of {@code <tableName>.} as {@code <alias>.} inside
     * the base SQL SELECT list, and inserts the alias after the main table name
     * in the FROM clause if it is missing.
     */
    private String normaliseMainTableAlias(String baseSql, AliasContext ctx) {
        if (!ctx.hasAlias()) return baseSql;

        // Replace "tableName." with "alias." in column references
        String regex = "\\b" + Pattern.quote(metadata.tableName()) + "\\.";
        String rewritten = baseSql.replaceAll(regex, ctx.alias() + ".");

        // Insert alias token after table name in FROM clause if missing
        StringBuilder sb = new StringBuilder(rewritten);
        injectAliasAfterTable(sb, metadata.tableName(), ctx.alias());
        return sb.toString();
    }

    /**
     * Inserts {@code alias} after the first occurrence of
     * {@code " FROM <tableName>"} when the token that follows is a SQL keyword
     * (JOIN, WHERE, etc.) rather than an explicit alias.
     */
    private static void injectAliasAfterTable(StringBuilder sql, String tableName, String alias) {
        String fromPattern = " FROM " + tableName;
        int idx = sql.indexOf(fromPattern);
        if (idx == -1) return;

        int afterTable = idx + fromPattern.length();
        int j = afterTable;
        while (j < sql.length() && Character.isWhitespace(sql.charAt(j))) j++;

        if (j >= sql.length()) {
            sql.insert(afterTable, " " + alias);
            return;
        }

        int k = j;
        while (k < sql.length() && !Character.isWhitespace(sql.charAt(k))) k++;
        String next = sql.substring(j, k).toUpperCase();

        Set<String> joinKeywords = Set.of("LEFT", "INNER", "RIGHT", "FULL", "CROSS", "JOIN", "WHERE", "ORDER", "GROUP", "HAVING", "LIMIT");
        if (next.isEmpty() || joinKeywords.contains(next)) {
            sql.insert(afterTable, " " + alias);
        }
    }

    // =========================================================================
    // JOIN composition
    // =========================================================================

    /**
     * Removes JOIN clauses baked into a pre-built SQL string.
     * Strips everything from the first JOIN keyword up to (but not including)
     * the first WHERE / ORDER BY / GROUP BY / HAVING / LIMIT token (case-insensitive).
     * Used when {@link FilterBuilder#notJoin()} is active and the base SQL
     * (e.g. from {@code metadata.selectSql()}) already contains joins.
     */
    private static String stripJoinsFromSql(String sql) {
        if (sql == null) return sql;
        // Find the first occurrence of a JOIN keyword (LEFT, INNER, RIGHT, FULL, CROSS JOIN or bare JOIN)
        java.util.regex.Matcher m = Pattern
                .compile("\\s+(LEFT|INNER|RIGHT|FULL|CROSS)?\\s*JOIN\\s", Pattern.CASE_INSENSITIVE)
                .matcher(sql);
        if (!m.find()) return sql; // no joins baked in
        int joinStart = m.start();

        // Find where the "real" SQL continues (WHERE / ORDER BY / GROUP BY / HAVING / LIMIT)
        java.util.regex.Matcher end = Pattern
                .compile("\\s+(WHERE|ORDER\\s+BY|GROUP\\s+BY|HAVING|LIMIT)\\b", Pattern.CASE_INSENSITIVE)
                .matcher(sql);
        int joinEnd = end.find(joinStart) ? end.start() : sql.length();

        return sql.substring(0, joinStart) + sql.substring(joinEnd);
    }

    /**
     * Appends all joins (metadata-defined + filter-defined) to {@code sql}.
     * Duplicates are detected and skipped.
     */
    private void appendJoins(StringBuilder sql, AliasContext ctx) {
        if (filter.isNoJoin()) return;
        appendMetadataJoins(sql, ctx);
        appendFilterJoins(sql, ctx);
    }

    /**
     * Appends joins defined via entity metadata annotations ({@code @DbJoin}).
     */
    private void appendMetadataJoins(StringBuilder sql, AliasContext ctx) {
        JoinInfo[] metaJoins = metadata.joinInfos();
        if (metaJoins == null || metaJoins.length == 0) return;

        String lower = sql.toString().toLowerCase();
        for (JoinInfo mj : metaJoins) {
            if (!isValidJoin(mj)) continue;
            if (joinAlreadyPresent(lower, mj.getTable(), mj.getAlias())) continue;

            String onClause = mj.getOn();
            // Requalify main table references in ON clause: tableName. -> alias.
            if (ctx.hasAlias()) {
                onClause = requalifyMainTable(onClause, metadata.tableName(), ctx.alias());
            }

            appendJoinClause(sql, mj.getType().toString(), mj.getTable(), mj.getAlias(), onClause);
            lower = sql.toString().toLowerCase();
        }
    }

    /**
     * Appends joins defined via {@link FilterBuilder}, skipping those already
     * present from metadata.
     */
    private void appendFilterJoins(StringBuilder sql, AliasContext ctx) {
        List<FilterBuilder.Join> filterJoins = filter.getJoins();
        if (filterJoins == null || filterJoins.isEmpty()) return;

        JoinInfo[] metaJoins = metadata.joinInfos();
        String lower = sql.toString().toLowerCase();

        for (FilterBuilder.Join fj : filterJoins) {
            if (fj == null) continue;
            if (isDuplicateFilterJoin(fj, metaJoins)) continue;
            if (joinAlreadyPresent(lower, fj.table(), fj.alias())) continue;

            appendJoinClause(sql, fj.type().toString(), fj.table(), fj.alias(), fj.on());
            lower = sql.toString().toLowerCase();
        }
    }

    private static boolean isValidJoin(JoinInfo mj) {
        return mj != null
                && mj.getTable() != null
                && mj.getOn() != null
                && !mj.getOn().isBlank();
    }

    private static boolean joinAlreadyPresent(String lowerSql, String table, String alias) {
        return lowerSql.contains(" join " + table.toLowerCase())
                || (alias != null && lowerSql.contains(" " + alias.toLowerCase() + " "));
    }

    private static boolean isDuplicateFilterJoin(FilterBuilder.Join fj, JoinInfo[] metaJoins) {
        if (metaJoins == null) return false;
        for (JoinInfo mj : metaJoins) {
            if (mj == null) continue;
            if (mj.getTable() != null
                    && mj.getAlias() != null
                    && mj.getTable().equalsIgnoreCase(fj.table())
                    && mj.getAlias().equals(fj.alias())) {
                return true;
            }
        }
        return false;
    }

    private static void appendJoinClause(
            StringBuilder sql, String type, String table, String alias, String on) {
        sql.append(' ').append(type).append(" JOIN ")
                .append(table).append(' ').append(alias)
                .append(" ON ").append(on);
    }

    // =========================================================================
    // WHERE / soft-delete
    // =========================================================================

    private void appendWhere(StringBuilder sql, AliasContext ctx) {
        // If the caller's filter already includes the soft-delete predicate (e.g. "active = true" or "deleted_at IS NULL"),
        // we must not emit the soft-delete clause again to avoid duplicates like "active = true AND active = true".
        String userClause = filter.getWhereClause();
        boolean userHasSoftDelete = false;
        if (userClause != null && !userClause.isBlank()) {
            String checkClause = userClause;
            if (ctx.hasAlias()) {
                checkClause = requalifyMainTable(checkClause, metadata.tableName(), ctx.alias());
                checkClause = qualifyBareColumns(checkClause, ctx.alias());
            }
            userHasSoftDelete = clauseContainsSoftDeletePredicate(checkClause);
        }

        boolean hasWhere = false;
        if (!filter.isIgnoreSoftDelete() && !userHasSoftDelete) {
            hasWhere = appendSoftDeleteClause(sql, ctx);
        }
        appendFilterClause(sql, hasWhere, ctx);
    }

    /**
     * Heuristic: detect if the provided WHERE clause already contains the soft-delete
     * predicate for this entity (active = true / active = ? or deleted_at IS NULL).
     */
    private boolean clauseContainsSoftDeletePredicate(String clause) {
        if (clause == null || clause.isBlank()) return false;
        String lower = clause.toLowerCase();
        try {
            if (metadata.hasActive()) {
                String col = metadata.activeColumn().toLowerCase();
                // look for patterns like "col = true", "col=true", or "col = ?"
                if (Pattern.compile(".*\\b" + Pattern.quote(col) + "\\b\\s*=\\s*(true|false|\\?).*").matcher(lower).matches()) {
                    return true;
                }
            }
            if (metadata.hasDeletedAt()) {
                String col = metadata.deletedAtColumn().toLowerCase();
                if (Pattern.compile(".*\\b" + Pattern.quote(col) + "\\b\\s+is\\s+null.*").matcher(lower).matches()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Emits the soft-delete predicate ({@code active = true} or
     * {@code deleted_at IS NULL}) when enabled.
     *
     * @return {@code true} if a WHERE clause was started
     */
    private boolean appendSoftDeleteClause(StringBuilder sql, AliasContext ctx) {
        if (filter.isIgnoreSoftDelete()) return false;

        String condition = null;
        if (metadata.hasActive()) {
            condition = ctx.qualifyOrNull(metadata.activeColumn()) + " = true";
        } else if (metadata.hasDeletedAt()) {
            condition = ctx.qualifyOrNull(metadata.deletedAtColumn()) + " IS NULL";
        }

        if (condition != null) {
            sql.append(WHERE).append(condition);
            return true;
        }
        return false;
    }

    /**
     * Appends the user-supplied WHERE clause, qualifying bare column names
     * with the main-table alias when one is active.
     */
    private void appendFilterClause(StringBuilder sql, boolean hasWhere, AliasContext ctx) {
        String clause = filter.getWhereClause();
        if (clause == null || clause.isBlank()) return;

        // First, replace tableName. with alias. in the clause
        if (ctx.hasAlias()) {
            clause = requalifyMainTable(clause, metadata.tableName(), ctx.alias());
        }

        // Then, qualify any bare columns that aren't already qualified
        if (ctx.hasAlias()) {
            clause = qualifyBareColumns(clause, ctx.alias());
        }
        sql.append(hasWhere ? AND : WHERE).append(clause);
    }

    /**
     * Prefixes unqualified column references in {@code clause} with
     * {@code alias + "."}.
     *
     * <p>Only columns known to the entity's SELECT list are touched; this
     * avoids inadvertently qualifying join-table columns. This method also
     * respects any existing qualifiers (e.g. alias.column) and won't double-qualify.
     */
    private String qualifyBareColumns(String clause, String alias) {
        for (String col : metadata.selectColumns()) {
            // Only replace unqualified column names (not preceded or followed by word chars or dots)
            // This regex avoids matching "mycol" when searching for "col", and avoids double-qualifying
            String regex = "(?<![.\\w])" + Pattern.quote(col) + "(?![.\\w])";
            clause = clause.replaceAll(regex, alias + "." + col);
        }
        return clause;
    }

    // =========================================================================
    // ORDER BY
    // =========================================================================

    private void appendOrderBy(StringBuilder sql, Pageable pageable, AliasContext ctx) {
        // 1. Pageable sort has highest priority
        Pageable.Sort sort = (pageable != null) ? pageable.sort() : null;
        if (sort != null && !isBlank(sort.property())) {
            dropExistingOrderBy(sql);
            String resolved = resolveAndQualify(sort.property(), ctx);
            sql.append(ORDER_BY).append(resolved)
                    .append(' ').append(sort.direction().name());
            return;
        }

        // 2. FilterBuilder ORDER BY
        if (filter.hasOrderBy()) {
            String raw = filter.buildOrderBy().trim();
            if (raw.toUpperCase().startsWith("ORDER BY ")) raw = raw.substring(9);
            sql.append(ORDER_BY).append(resolveOrderByBody(raw, ctx));
            return;
        }

        // 3. Metadata default
        String defaultOrder = metadata.defaultOrderBy();
        if (!isBlank(defaultOrder)) {
            sql.append(ORDER_BY).append(defaultOrder);
            return;
        }

        // 4. @OrderBy annotation
        OrderBy orderByAnn = metadata.entityClass().getAnnotation(OrderBy.class);
        if (orderByAnn != null && !isBlank(orderByAnn.value())) {
            sql.append(ORDER_BY).append(orderByAnn.value());
            return;
        }

        // 5. First label in SELECT list
        List<String> labels = metadata.selectLabels();
        if (labels != null && !labels.isEmpty()) {
            sql.append(ORDER_BY).append(labels.get(0));
        }
    }

    private String resolveOrderByBody(String body, AliasContext ctx) {
        String[] parts = body.split(",");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) out.append(", ");
            String part = parts[i].trim();
            // Replace tableName. with alias. before processing
            if (ctx.hasAlias()) {
                part = requalifyMainTable(part, metadata.tableName(), ctx.alias());
            }
            // split into token + optional direction
            int space = part.indexOf(' ');
            String token     = (space == -1) ? part : part.substring(0, space);
            String direction = (space == -1) ? "" : part.substring(space); // includes leading space
            out.append(resolveAndQualify(token, ctx)).append(direction);
        }
        return out.toString();
    }

    /** Resolves a property/column token and qualifies it with the main-table alias. */
    private String resolveAndQualify(String token, AliasContext ctx) {
        String resolved = resolveOrderToken(token);
        // Qualify unqualified tokens when an alias is active and the resolved token
        // does not already carry a table/alias qualifier.
        if (ctx.hasAlias() && !resolved.contains(".")) {
            return ctx.alias() + "." + resolved;
        }
        return resolved;
    }

    private static void dropExistingOrderBy(StringBuilder sql) {
        int idx = sql.indexOf(" ORDER BY ");
        if (idx != -1) sql.setLength(idx);
    }

    // =========================================================================
    // GROUP BY
    // =========================================================================

    /**
     * Appends the GROUP BY clause from the filter, qualifying bare column names
     * with the main-table alias when one is active.
     */
    private void appendGroupBy(StringBuilder sql, AliasContext ctx) {
        String raw = filter.buildGroupBy().trim();
        if (raw.isBlank()) return;

        // Remove "GROUP BY " prefix if present
        if (raw.toUpperCase().startsWith("GROUP BY ")) {
            raw = raw.substring(9);
        }

        String resolved = resolveGroupByBody(raw, ctx);
        sql.append(" GROUP BY ").append(resolved);
    }

    /**
     * Resolves the GROUP BY body by parsing column tokens and applying alias qualification.
     * Similar to ORDER BY resolution but without direction specifiers.
     */
    private String resolveGroupByBody(String body, AliasContext ctx) {
        String[] parts = body.split(",");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) out.append(", ");
            String part = parts[i].trim();
            // Replace tableName. with alias. before processing
            if (ctx.hasAlias()) {
                part = requalifyMainTable(part, metadata.tableName(), ctx.alias());
            }
            out.append(resolveAndQualify(part, ctx));
        }
        return out.toString();
    }

    // =========================================================================
    // LIMIT / OFFSET
    // =========================================================================

    private void appendLimitOffset(StringBuilder sql, Pageable pageable, boolean fetchOneMore) {
        int  pageSize = Math.max(1, pageable.pageSize());
        int  limit    = fetchOneMore ? pageSize + 1 : pageSize;
        long offset   = Math.max(0L, pageable.getOffset());

        if (dialect != null) {
            String replaced = dialect.applyPagination(sql.toString(), limit, (int) offset);
            sql.setLength(0);
            sql.append(replaced);
        } else {
            sql.append(" LIMIT ").append(limit).append(" OFFSET ").append(offset);
        }
    }

    // =========================================================================
    // CTEs / window functions
    // =========================================================================

    private String applyCtesAndWindowFunctions(String baseSql) {
        StringBuilder out = new StringBuilder();

        // --- CTEs ---
        List<FilterBuilder.Cte> ctes = filter.getCtes();
        if (ctes != null && !ctes.isEmpty()) {
            out.append("WITH ");
            boolean first = true;
            for (FilterBuilder.Cte c : ctes) {
                if (!first) out.append(", ");
                first = false;
                out.append(c.name()).append(" AS (");
                if (!isBlank(c.sql())) {
                    out.append(c.sql());
                } else if (c.subQuery() != null) {
                    renderSubqueryCte(out, c);
                }
                out.append(")");
            }
            out.append(' ');
        }

        // --- Window functions injected into SELECT list ---
        List<FilterBuilder.WindowFunction> wfs = filter.getWindowFunctions();
        if (wfs == null || wfs.isEmpty()) {
            return out.append(baseSql).toString();
        }

        String upper = baseSql.toUpperCase();
        int fromIdx = upper.indexOf(" FROM ");
        if (fromIdx == -1) {
            return out.append(baseSql).toString();
        }

        String selectPart = baseSql.substring(0, fromIdx);
        String rest        = baseSql.substring(fromIdx);
        out.append(selectPart);
        for (FilterBuilder.WindowFunction wf : wfs) {
            out.append(", ").append(wf.expression()).append(" AS ").append(wf.alias());
        }
        return out.append(rest).toString();
    }

    private void renderSubqueryCte(StringBuilder out, FilterBuilder.Cte c) {
        try {
            FilterBuilder sq      = c.subQuery();
            String        subFrom = sq.buildFromClause(metadata.tableName(), sq.getJoins());
            String        where   = sq.getWhereClause();
            out.append("SELECT ").append(metadata.tableName()).append(".* ").append(subFrom);
            if (!isBlank(where)) out.append(" WHERE ").append(where);
            String order = sq.buildOrderBy();
            if (!isBlank(order)) out.append(order);
        } catch (Exception ignored) {
            // fallback: empty body — will result in invalid SQL at runtime, but
            // a CTE without a body is already a programming error caught early.
        }
    }

    // =========================================================================
    // ORDER BY token resolution
    // =========================================================================

    /**
     * Resolves an ORDER BY token (camelCase property name or SQL column) to
     * the canonical SQL column reference.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Qualified token ({@code alias.property}): right-hand side is mapped.</li>
     *   <li>Exact match in SELECT columns.</li>
     *   <li>Entity property → DB column mapping via {@code metadata.columnForProperty}.</li>
     *   <li>snake_case conversion of the token.</li>
     *   <li>Join column search.</li>
     * </ol>
     *
     * @param token raw ORDER BY token
     * @return resolved SQL token
     * @throws IllegalArgumentException if the token cannot be resolved
     */
    private String resolveOrderToken(String token) {
        if (token == null) throw new IllegalArgumentException("ORDER BY token is null");
        token = token.trim();

        // Leave complex expressions (function calls, arithmetic, quoted identifiers) as-is
        if (isComplexExpression(token)) return token;

        // --- Qualified token (alias.property) ---
        if (token.contains(".")) {
            String[] parts = token.split("\\.", 2);
            String left    = parts[0];
            String right   = parts[1];

            // Validate both parts are safe identifiers to prevent injection
            validateIdentifier(left);
            validateIdentifier(right);

            // Try property mapping
            String mapped = metadata.columnForProperty(right);
            if (mapped != null) return left + "." + mapped;

            // Exact match in select/join columns
            if (metadata.selectColumns().contains(right)) return left + "." + right;
            for (JoinInfo ji : metadata.joinInfos()) {
                if (ji != null && ji.getJoinColumnNames().contains(right)) return left + "." + right;
            }

            // snake_case variant
            String snake = toSnakeCase(right);
            if (metadata.selectColumns().contains(snake)) return left + "." + snake;
            for (JoinInfo ji : metadata.joinInfos()) {
                if (ji != null && ji.getJoinColumnNames().contains(snake)) return left + "." + snake;
            }

            throw new IllegalArgumentException(
                    "Unknown ORDER BY column '%s' for entity %s"
                            .formatted(right, metadata.entityClass().getSimpleName()));
        }

        // --- Unqualified token ---
        validateIdentifier(token);

        // 1. Property → DB column mapping
        String mapped = metadata.columnForProperty(token);
        if (mapped != null) {
            if (metadata.selectColumns().contains(mapped)) return mapped;
            for (JoinInfo ji : metadata.joinInfos()) {
                if (ji != null && ji.getJoinColumnNames().contains(mapped))
                    return ji.getAlias() + "." + mapped;
            }
            return metadata.tableName() + "." + mapped;
        }

        // 2. Exact match in SELECT list
        if (metadata.selectColumns().contains(token)) return token;

        // 3. Join column search
        for (JoinInfo ji : metadata.joinInfos()) {
            if (ji != null && ji.getJoinColumnNames().contains(token))
                return ji.getAlias() + "." + token;
        }

        // 4. snake_case variant on main table and joins
        String snake = toSnakeCase(token);
        if (metadata.selectColumns().contains(snake)) return metadata.tableName() + "." + snake;
        for (JoinInfo ji : metadata.joinInfos()) {
            if (ji != null && ji.getJoinColumnNames().contains(snake))
                return ji.getAlias() + "." + snake;
        }

        throw new IllegalArgumentException(
                "Unknown ORDER BY property '%s' for entity %s"
                        .formatted(token, metadata.entityClass().getSimpleName()));
    }

    // =========================================================================
    // Security
    // =========================================================================

    /**
     * Validates that an identifier (table/column/alias name) is safe against
     * SQL injection. Only word characters and dots are permitted.
     *
     * @param identifier the identifier to validate
     * @throws IllegalArgumentException if the identifier contains unsafe characters
     */
    private static void validateIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) return;
        if (!SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                    "Potentially unsafe SQL identifier rejected: '%s'".formatted(identifier));
        }
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    /** Replaces {@code tableName.} with {@code newAlias.} in an SQL fragment. */
    private static String requalifyMainTable(String sql, String tableName, String newAlias) {
        return sql.replaceAll("\\b" + Pattern.quote(tableName) + "\\.", newAlias + ".");
    }

    /**
     * Returns {@code true} when the token represents a complex SQL expression
     * that should not be modified (function calls, quoted identifiers, etc.).
     */
    private static boolean isComplexExpression(String token) {
        return token.contains("(")
                || token.contains(" ")
                || token.contains("*")
                || token.contains("\"")
                || token.contains("'");
    }

    /**
     * Converts a camelCase or PascalCase identifier to {@code snake_case}.
     *
     * <p>Examples: {@code consultationDate} → {@code consultation_date},
     * {@code id} → {@code id}, {@code patientID} → {@code patient_i_d}
     * (callers are expected to rely on {@code metadata.columnForProperty} first).
     */
    private static String toSnakeCase(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}