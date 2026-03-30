package br.com.liviacare.worm.orm.sql;

import br.com.liviacare.worm.annotation.mapping.OrderBy;
import br.com.liviacare.worm.orm.dialect.SqlDialect;
import br.com.liviacare.worm.orm.registry.EntityMetadata;
import br.com.liviacare.worm.orm.registry.JoinInfo;
import br.com.liviacare.worm.orm.registry.ProjectionMetadata;
import br.com.liviacare.worm.orm.sql.ast.*;
import br.com.liviacare.worm.query.FilterBuilder;
import br.com.liviacare.worm.query.Pageable;
import br.com.liviacare.worm.util.AliasUtils;

import java.util.*;
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

    private static final SqlCompiler SQL_COMPILER = new SqlCompiler();

    // -------------------------------------------------------------------------
    // SQL keyword fragments — centralised for consistency
    // -------------------------------------------------------------------------
    private static final String WHERE   = " WHERE ";
    private static final String AND     = " AND ";
    private static final String ORDER_BY = " ORDER BY ";

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
     * <p>The compiled SQL is cached in {@link QueryPlanCache} keyed on the structural
     * shape of this query so repeated calls with identical filter templates hit
     * the cache and skip AST construction entirely.
     *
     * @param pageable     pagination/sort descriptor; may be null
     * @param fetchOneMore when {@code true}, fetches {@code pageSize + 1} rows
     *                     (cursor-style "has next page" detection)
     * @return complete SQL string
     */
    public String buildSelectSql(Pageable pageable, boolean fetchOneMore) {
        QueryPlanKey key = buildKey(pageable, fetchOneMore, "select");
        return QueryPlanCache.get(key, () -> buildSelectSqlInternal(pageable, fetchOneMore));
    }

    private String buildSelectSqlInternal(Pageable pageable, boolean fetchOneMore) {
        AliasContext ctx = buildAliasContext();
        String baseSql  = applyCtesAndWindowFunctions(metadata.selectSql());
        baseSql = normaliseMainTableAlias(baseSql, ctx);
        if (filter.isNoJoin()) baseSql = stripJoinsFromSql(baseSql);

        List<JoinNode> joins = filter.isNoJoin() ? List.of() : buildFilterJoinNodes(baseSql, ctx);
        String joinsClause = renderJoinNodes(joins);
        WhereNode whereNode = buildWhereNode(baseSql + joinsClause, ctx);
        String whereClause = renderWhereNode(whereNode);
        String groupByClause = buildGroupByClause(baseSql + joinsClause + whereClause, ctx);
        String orderByClause = buildOrderByClause(baseSql + joinsClause + whereClause + groupByClause, pageable, ctx);
        String paginationClause = pageable != null
                ? buildPaginationClause(baseSql + joinsClause + whereClause + groupByClause + orderByClause, pageable, fetchOneMore)
                : "";

        return SQL_COMPILER.visitSelect(new SelectNode(baseSql, joins, whereNode, groupByClause, orderByClause, paginationClause));
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

        // Use the same alias resolution strategy as entity SELECT path. Projections
        // may contain their own table alias tokens (e.g. appointmentSummaryProjection)
        // generated during build; when the caller's filter requests a main-table
        // alias we must requalify the SELECT list and FROM clause so qualifiers
        // match the alias used in WHERE / JOIN fragments.
        AliasContext ctx = buildAliasContext();
        // Best-effort: replace projection-class-derived alias token (e.g. appointmentSummaryProjection)
        // with the resolved main alias so SELECT qualifiers match FROM/JOIN/WHERE.
        try {
            try {
                String projAlias = AliasUtils.defaultMainAlias(AliasUtils.entityTableName(projMeta.projectionClass()));
                if (projAlias != null && !projAlias.isBlank()) {
                    String targetAlias = ctx.hasAlias() ? ctx.alias() : AliasUtils.defaultMainAlias(metadata.tableName());
                    baseSql = replaceQualifiedPrefixIgnoreCase(baseSql, projAlias, targetAlias);
                }
            } catch (Exception ignored) {
                // ignore
            }
        } catch (Exception ignored) {
            // ignore
        }
        if (ctx.hasAlias()) {
            baseSql = requalifyUnknownQualifiers(baseSql, ctx);
        }
        baseSql = normaliseMainTableAlias(baseSql, ctx);
        if (filter.isNoJoin()) baseSql = stripJoinsFromSql(baseSql);

        List<JoinNode> joins = filter.isNoJoin() ? List.of() : buildFilterJoinNodes(baseSql, ctx);
        String joinsClause = renderJoinNodes(joins);
        WhereNode whereNode = buildWhereNode(baseSql + joinsClause, ctx);
        String whereClause = renderWhereNode(whereNode);
        String orderByClause = buildOrderByClause(baseSql + joinsClause + whereClause, pageable, ctx);
        String paginationClause = pageable != null
                ? buildPaginationClause(baseSql + joinsClause + whereClause + orderByClause, pageable, fetchOneMore)
                : "";

        return SQL_COMPILER.visitSelect(new SelectNode(baseSql, joins, whereNode, "", orderByClause, paginationClause));
    }

    private List<JoinNode> buildFilterJoinNodes(String baseSql, AliasContext ctx) {
        StringBuilder sql = new StringBuilder(baseSql);
        int start = sql.length();
        appendFilterJoins(sql, ctx);
        String fragment = sql.substring(start).trim();
        if (fragment.isEmpty()) {
            return List.of();
        }
        List<JoinNode> nodes = new ArrayList<>();
        for (FilterBuilder.Join join : filter.getJoins()) {
            if (join == null) continue;
            // Ensure join.ON references to the main table use the resolved alias.
            String on = join.on();
            if (on != null && ctx.hasAlias()) {
                // Replace occurrences of the table name (e.g. "appointments.") with the resolved alias
                on = replaceQualifiedPrefixIgnoreCase(on, metadata.tableName(), ctx.alias());
                // Also replace the historic placeholder alias 'a' (used by some helpers) with the resolved alias
                on = replaceQualifiedPrefixIgnoreCase(on, "a", ctx.alias());
                // If the filter explicitly provided a main alias, ensure it's used
                String provided = filter.getMainTableAlias();
                if (provided != null && !provided.isBlank() && !provided.equals(ctx.alias())) {
                    on = replaceQualifiedPrefixIgnoreCase(on, provided, ctx.alias());
                }
            }
            nodes.add(new JoinNode(join.type().toString(), join.table(), join.alias(), new ConditionNode(on)));
        }
        return nodes;
    }

    private WhereNode buildWhereNode(String sqlPrefix, AliasContext ctx) {
        StringBuilder sql = new StringBuilder(sqlPrefix);
        int start = sql.length();
        appendWhere(sql, ctx);
        String fragment = sql.substring(start).trim();
        if (fragment.isEmpty()) {
            return null;
        }
        String body = fragment.toUpperCase().startsWith("WHERE ") ? fragment.substring(6) : fragment;
        List<ConditionNode> conditions = new ArrayList<>();
        int andIndex = body.indexOf(" AND ");
        if (andIndex >= 0) {
            conditions.add(new ConditionNode(body.substring(0, andIndex)));
            conditions.add(new ConditionNode(body.substring(andIndex + 5)));
        } else {
            conditions.add(new ConditionNode(body));
        }
        return new WhereNode(conditions, " AND ");
    }

    private static String renderJoinNodes(List<JoinNode> joins) {
        if (joins == null || joins.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (JoinNode join : joins) {
            sb.append(' ').append(join.type()).append(" JOIN ").append(join.table())
                    .append(' ').append(join.alias()).append(" ON ").append(join.onCondition().expression());
        }
        return sb.toString();
    }

    private static String renderWhereNode(WhereNode whereNode) {
        if (whereNode == null || whereNode.conditions().isEmpty()) return "";
        StringBuilder sb = new StringBuilder(" WHERE ");
        for (int i = 0; i < whereNode.conditions().size(); i++) {
            if (i > 0) sb.append(whereNode.separator());
            sb.append(whereNode.conditions().get(i).expression());
        }
        return sb.toString();
    }

    /**
     * Replace occurrences of a qualified token like "token." with "replacement."
     * matching case-insensitively and ensuring the token is not part of a larger
     * identifier (uses the shared isIdentifierOrDot helper available in this class).
     */
    private static String replaceQualifiedPrefixIgnoreCase(String input, String token, String replacement) {
        if (input == null || input.isEmpty() || token == null || token.isBlank()) return input;
        int fromIndex = 0;
        StringBuilder out = null;
        int tokenLen = token.length();
        while (true) {
            int pos = indexOfQualifiedTokenIgnoreCase(input, token, fromIndex);
            if (pos < 0) break;
            if (out == null) out = new StringBuilder(input.length() + 16);
            out.append(input, fromIndex, pos);
            out.append(replacement).append('.');
            fromIndex = pos + tokenLen + 1;
        }
        if (out == null) return input;
        out.append(input, fromIndex, input.length());
        return out.toString();
    }

    private static int indexOfQualifiedTokenIgnoreCase(String input, String token, int fromIndex) {
        int tokenLen = token.length();
        int max = input.length() - tokenLen;
        for (int i = Math.max(0, fromIndex); i <= max; i++) {
            if (!input.regionMatches(true, i, token, 0, tokenLen)) continue;
            if (i > 0 && isIdentifierOrDot(input.charAt(i - 1))) continue;
            int end = i + tokenLen;
            if (end >= input.length() || input.charAt(end) != '.') continue;
            return i;
        }
        return -1;
    }

    

    private String buildGroupByClause(String sqlPrefix, AliasContext ctx) {
        StringBuilder sql = new StringBuilder(sqlPrefix);
        int start = sql.length();
        appendGroupBy(sql, ctx);
        return sql.substring(start);
    }

    private String buildOrderByClause(String sqlPrefix, Pageable pageable, AliasContext ctx) {
        StringBuilder sql = new StringBuilder(sqlPrefix);
        int start = sql.length();
        appendOrderBy(sql, pageable, ctx);
        return sql.substring(start);
    }

    private String buildPaginationClause(String sqlPrefix, Pageable pageable, boolean fetchOneMore) {
        StringBuilder sql = new StringBuilder(sqlPrefix);
        int start = sql.length();
        appendLimitOffset(sql, pageable, fetchOneMore);
        return sql.substring(start);
    }

    // =========================================================================
    // Public API — COUNT / EXISTS
    // =========================================================================

    /**
     * Builds a {@code SELECT COUNT(*)} query for the entity.
     *
     * <p>The compiled SQL is cached via {@link QueryPlanCache}.
     *
     * @return complete SQL string
     */
    public String buildCountSql() {
        QueryPlanKey key = buildKey(null, false, "count");
        return QueryPlanCache.get(key, () -> "SELECT COUNT(*)" + buildFromJoinsAndWhere());
    }

    /**
     * Builds a {@code SELECT 1 … LIMIT 1} existence-check query.
     *
     * <p>The compiled SQL is cached via {@link QueryPlanCache}.
     *
     * @return complete SQL string
     */
    public String buildExistsSql() {
        QueryPlanKey key = buildKey(null, false, "exists");
        return QueryPlanCache.get(key, () -> {
            String sql = "SELECT 1" + buildFromJoinsAndWhere();
            return (dialect != null) ? dialect.applyPagination(sql, 1, 0) : sql + " LIMIT 1";
        });
    }

    // =========================================================================
    // Query plan key helpers
    // =========================================================================

    /**
     * Computes a structural {@link QueryPlanKey} for caching.
     * The key captures the query <em>shape</em> — WHERE template, joins, ordering,
     * and pagination — without any actual bind-parameter values.
     */
    private QueryPlanKey buildKey(Pageable pageable, boolean fetchOneMore, String queryType) {
        String orderShape = buildOrderShape(pageable);
        int pageSize   = pageable != null ? pageable.pageSize()   : -1;
        int pageOffset = pageable != null ? (int) Math.max(0, pageable.getOffset()) : 0;
        return new QueryPlanKey(
                metadata.entityClass(),
                filter.getWhereClause(),
                filter.getJoins().hashCode(),
                orderShape,
                pageSize,
                pageOffset,
                fetchOneMore,
                filter.isNoJoin(),
                filter.isIgnoreSoftDelete(),
                buildCteShape(),
                queryType
        );
    }

    /** Builds a compact string representing the ORDER BY shape (filter + pageable). */
    private String buildOrderShape(Pageable pageable) {
        String filterOrder = filter.hasOrderBy() ? filter.buildOrderBy() : null;
        if (pageable != null && pageable.sort() != null && !isBlank(pageable.sort().property())) {
            String ps = pageable.sort().property() + ":" + pageable.sort().direction();
            return filterOrder != null ? filterOrder + "|" + ps : ps;
        }
        return filterOrder;
    }

    /** Builds a compact fingerprint for CTEs and window functions. */
    private String buildCteShape() {
        List<FilterBuilder.Cte> ctes = filter.getCtes();
        List<FilterBuilder.WindowFunction> wfs = filter.getWindowFunctions();
        if ((ctes == null || ctes.isEmpty()) && (wfs == null || wfs.isEmpty())) return null;
        StringBuilder sb = new StringBuilder();
        if (ctes != null) for (FilterBuilder.Cte c : ctes) sb.append(c.name()).append(';');
        if (wfs != null)  for (FilterBuilder.WindowFunction w : wfs) sb.append(w.expression()).append(':').append(w.alias()).append(';');
        return sb.toString();
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
    private record AliasContext(boolean hasAlias, String alias, AliasRegistry registry) {
        static final AliasContext NONE = new AliasContext(false, null, null);

        static AliasContext of(boolean hasAlias, String alias, AliasRegistry registry) {
            return hasAlias ? new AliasContext(true, alias, registry) : NONE;
        }

        String qualifyOrNull(String column) {
            return hasAlias ? alias + "." + column : column;
        }

        public AliasRegistry registry() { return registry; }
    }

    private AliasContext buildAliasContext() {
        String explicitAlias = blankToNull(filter.getMainTableAlias());
        boolean hasFilterJoins = !filter.getJoins().isEmpty();
        boolean hasMetaJoins = hasAnyValidMetadataJoin();
       boolean sqlHasJoin = metadata.selectSql() != null && metadata.selectSql().toUpperCase().contains(" JOIN ");
        boolean needsAlias   = explicitAlias != null || hasFilterJoins || hasMetaJoins || sqlHasJoin;

        // Create registry and register any user-supplied join aliases to avoid collisions
        AliasRegistry registry = new AliasRegistry();
        filter.getJoins().forEach(j -> {
            if (j != null && j.alias() != null && !j.alias().isBlank()) registry.registerUsedAlias(j.alias());
        });

        // Prefer class-name-derived alias for the main entity. Resolve root entity class via metadata
        Class<?> entityClass = metadata.entityClass();
        String resolved;
        if (explicitAlias != null) {
            resolved = registry.registerWithAlias(entityClass, explicitAlias);
        } else {
            resolved = registry.register(entityClass);
        }

        return AliasContext.of(needsAlias, resolved, registry);
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

        // Fast-path: skip if tableName doesn't appear in SQL with dot
        String tableNameWithDot = metadata.tableName() + ".";
        if (!baseSql.contains(tableNameWithDot)) {
            // Still need to ensure alias exists in FROM clause
            StringBuilder sb = new StringBuilder(baseSql);
            injectAliasAfterTable(sb, metadata.tableName(), ctx.alias());
            return sb.toString();
        }

        // Replace "tableName." with "alias." in column references
        String rewritten = requalifyMainTable(baseSql, metadata.tableName(), ctx.alias());

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
        // If the token that follows the table name is a SQL keyword (no explicit alias),
        // insert the alias. Otherwise the token is an explicit alias or 'AS' form and
        // should be replaced with the requested alias to keep aliases consistent.
        if (next.isEmpty() || joinKeywords.contains(next)) {
            sql.insert(afterTable, " " + alias);
            return;
        }

        // Handle explicit "AS <alias>" syntax
        if ("AS".equals(next)) {
            int afterAs = k;
            while (afterAs < sql.length() && Character.isWhitespace(sql.charAt(afterAs))) afterAs++;
            if (afterAs >= sql.length()) {
                // malformed SQL, just insert alias after table
                sql.insert(afterTable, " " + alias);
                return;
            }
            int aliasEnd = afterAs;
            while (aliasEnd < sql.length() && !Character.isWhitespace(sql.charAt(aliasEnd))) aliasEnd++;
            // replace existing alias after AS with the requested alias
            sql.replace(afterAs, aliasEnd, alias);
            return;
        }

        // Next token is an explicit alias (e.g. "FROM authors author"). Replace it
        // with the requested alias so SELECT list and other clauses stay consistent.
        sql.replace(j, k, alias);
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

            // Ensure join alias respects class-based aliasing when possible. If metadata provided an alias, try to register it;
            // otherwise derive from join class.
            String useAlias = mj.getAlias();
            try {
                if (ctx.registry() != null) {
                    if (useAlias == null || useAlias.isBlank()) {
                        useAlias = ctx.registry().register(mj.getJoinClass());
                    } else {
                        useAlias = ctx.registry().registerWithAlias(mj.getJoinClass(), useAlias);
                    }
                }
            } catch (Exception ignored) {
                // fallback to provided alias
            }

            appendJoinClause(sql, mj.getType().toString(), mj.getTable(), useAlias, onClause);
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

            String useAlias = fj.alias();
                if (ctx.registry() != null && (useAlias == null || useAlias.isBlank())) {
                    // register the raw table-derived alias to ensure uniqueness
                    ctx.registry().registerUsedAlias(AliasUtils.defaultJoinAlias(fj.table()));
            } else if (ctx.registry() != null) {
                ctx.registry().registerUsedAlias(useAlias);
            }
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
        if (metadata.hasActive() && containsEqualityPredicate(lower, metadata.activeColumn().toLowerCase())) {
            return true;
        }
        if (metadata.hasDeletedAt() && containsIsNullPredicate(lower, metadata.deletedAtColumn().toLowerCase())) {
            return true;
        }
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

        clause = normalizePropertyTokensToColumns(clause);

        String aliasToUse = ctx.hasAlias() ? ctx.alias() : null;
        if (aliasToUse == null) {
            // Use table-name-derived fallback alias for consistency with main alias resolution
            String fallbackAlias = AliasUtils.defaultMainAlias(metadata.tableName());
            String fromProbe = (" FROM " + metadata.tableName() + " " + fallbackAlias).toUpperCase();
            if (sql.toString().toUpperCase().contains(fromProbe)) {
                aliasToUse = fallbackAlias;
            }
        }

        // First, replace tableName. with alias. in the clause
        if (aliasToUse != null) {
            clause = requalifyMainTable(clause, metadata.tableName(), aliasToUse);
        }

        // Then, qualify any bare columns that aren't already qualified
        if (aliasToUse != null) {
            clause = qualifyBareColumns(clause, aliasToUse);
            // Safety net: always qualify the entity ID column under alias mode.
            // This keeps by-id filters deterministic even if selectColumns metadata changes.
            clause = replaceIdentifierToken(clause, metadata.idColumnName(), aliasToUse + "." + metadata.idColumnName(), false);
        }
        sql.append(hasWhere ? AND : WHERE).append(clause);
    }

    private String normalizePropertyTokensToColumns(String clause) {
        // Fast-path: if clause contains no underscores, skip camelCase normalization entirely
        if (!clause.contains("_") && clause.equals(clause.toLowerCase())) {
            return clause;
        }
        String normalized = clause;
        for (String col : metadata.selectColumns()) {
            String camel = toCamelCase(col);
            if (camel.equals(col)) continue;
            // Avoid regex if camelCase form doesn't appear in clause
            if (!normalized.contains(camel)) continue;
            normalized = replaceIdentifierToken(normalized, camel, col, false);
        }
        return normalized;
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
        if (alias == null || alias.isBlank()) return clause;
        
        String result = clause;
        for (String col : metadata.selectColumns()) {
            // Fast-path: skip if column not in clause
            if (!result.contains(col)) continue;
            result = replaceIdentifierToken(result, col, alias + "." + col, false);
        }
        return result;
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
        for (int i = 0; i < identifier.length(); i++) {
            char ch = identifier.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '.') {
                continue;
            }
            throw new IllegalArgumentException(
                    "Potentially unsafe SQL identifier rejected: '%s'".formatted(identifier));
        }
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    /** Replaces {@code tableName.} with {@code newAlias.} in an SQL fragment. */
    private static String requalifyMainTable(String sql, String tableName, String newAlias) {
        return replaceQualifiedPrefix(sql, tableName, newAlias, true);
    }

    /**
     * Heuristic: find occurrences of <qualifier>.<column> where <column> is
     * one of the main table's select columns and qualifier != tableName,
     * then replace qualifier with the resolved alias from ctx.
     */
    private String requalifyUnknownQualifiers(String sql, AliasContext ctx) {
        if (sql == null || ctx == null || !ctx.hasAlias()) return sql;
        String out = sql;
        for (String col : metadata.selectColumns()) {
            if (col == null || col.isBlank()) continue;
            // match pattern like: <qual>.<col> (case-insensitive)
            java.util.regex.Matcher m = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\." + Pattern.quote(col), Pattern.CASE_INSENSITIVE).matcher(out);
            StringBuffer sb = new StringBuffer();
            boolean changed = false;
            while (m.find()) {
                String qual = m.group(1);
                if (qual.equalsIgnoreCase(metadata.tableName())) continue;
                if (qual.equalsIgnoreCase(ctx.alias())) continue;
                // Replace with ctx.alias()
                m.appendReplacement(sb, ctx.alias() + "." + col);
                changed = true;
            }
            m.appendTail(sb);
            if (changed) out = sb.toString();
        }
        return out;
    }

    private static boolean containsEqualityPredicate(String clause, String token) {
        int fromIndex = 0;
        while (true) {
            int pos = indexOfToken(clause, token, fromIndex, false, false);
            if (pos < 0) return false;

            int i = pos + token.length();
            while (i < clause.length() && Character.isWhitespace(clause.charAt(i))) i++;
            if (i < clause.length() && clause.charAt(i) == '=') {
                i++;
                while (i < clause.length() && Character.isWhitespace(clause.charAt(i))) i++;
                if (i < clause.length()) {
                    if (clause.startsWith("true", i) || clause.startsWith("false", i) || clause.charAt(i) == '?') {
                        return true;
                    }
                }
            }
            fromIndex = pos + token.length();
        }
    }

    private static boolean containsIsNullPredicate(String clause, String token) {
        int fromIndex = 0;
        while (true) {
            int pos = indexOfToken(clause, token, fromIndex, false, false);
            if (pos < 0) return false;

            int i = pos + token.length();
            while (i < clause.length() && Character.isWhitespace(clause.charAt(i))) i++;
            if (i + 2 <= clause.length() && clause.startsWith("is", i)) {
                i += 2;
                while (i < clause.length() && Character.isWhitespace(clause.charAt(i))) i++;
                if (i + 4 <= clause.length() && clause.startsWith("null", i)) {
                    return true;
                }
            }
            fromIndex = pos + token.length();
        }
    }

    private static String replaceQualifiedPrefix(String input, String token, String replacement, boolean ignoreCase) {
        int fromIndex = 0;
        StringBuilder out = null;
        while (true) {
            int pos = indexOfToken(input, token, fromIndex, true, ignoreCase);
            if (pos < 0) break;
            if (out == null) out = new StringBuilder(input.length() + 16);
            out.append(input, fromIndex, pos);
            out.append(replacement).append('.');
            fromIndex = pos + token.length() + 1;
        }
        if (out == null) return input;
        out.append(input, fromIndex, input.length());
        return out.toString();
    }

    private static String replaceIdentifierToken(String input, String token, String replacement, boolean ignoreCase) {
        int fromIndex = 0;
        StringBuilder out = null;
        while (true) {
            int pos = indexOfToken(input, token, fromIndex, false, ignoreCase);
            if (pos < 0) break;
            if (out == null) out = new StringBuilder(input.length() + 16);
            out.append(input, fromIndex, pos);
            out.append(replacement);
            fromIndex = pos + token.length();
        }
        if (out == null) return input;
        out.append(input, fromIndex, input.length());
        return out.toString();
    }

    private static int indexOfToken(String input, String token, int fromIndex, boolean requireDotAfter, boolean ignoreCase) {
        if (input == null || token == null || token.isEmpty()) return -1;
        int max = input.length() - token.length();
        for (int i = Math.max(0, fromIndex); i <= max; i++) {
            if (!regionMatches(input, i, token, ignoreCase)) continue;
            if (i > 0 && isIdentifierOrDot(input.charAt(i - 1))) continue;
            int end = i + token.length();
            if (requireDotAfter) {
                if (end >= input.length() || input.charAt(end) != '.') continue;
            } else {
                if (end < input.length() && isIdentifierOrDot(input.charAt(end))) continue;
            }
            return i;
        }
        return -1;
    }

    private static boolean regionMatches(String input, int inputOffset, String token, boolean ignoreCase) {
        if (ignoreCase) {
            return input.regionMatches(true, inputOffset, token, 0, token.length());
        }
        return input.regionMatches(inputOffset, token, 0, token.length());
    }

    private static boolean isIdentifierOrDot(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '.';
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

    private static String toCamelCase(String s) {
        if (s == null || s.isEmpty()) return s;
        String[] parts = s.split("_");
        if (parts.length == 0) return s;
        StringBuilder out = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].isBlank()) continue;
            out.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return out.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}