package com.github.rfdetoni.worm.orm.sql.ast;

import java.util.List;

/**
 * Immutable AST node representing a SELECT statement already normalized into logical clauses.
 *
 * <p>Strings are still used for individual clause payloads in this first migration step,
 * but the node boundary enables deterministic visitor compilation and future query caching.
 */
public record SelectNode(
        String selectFrom,
        List<JoinNode> joins,
        WhereNode whereClause,
        String groupByClause,
        String orderByClause,
        String paginationClause
) implements SqlAstNode {
}

