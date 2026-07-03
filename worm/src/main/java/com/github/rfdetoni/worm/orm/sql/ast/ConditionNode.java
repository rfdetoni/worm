package com.github.rfdetoni.worm.orm.sql.ast;

/**
 * Smallest SQL predicate unit in the current AST.
 *
 * <p>The expression is still stored as a pre-normalized SQL fragment, but moving it into
 * its own node enables future predicate-level caching and richer logical composition.
 */
public record ConditionNode(String expression) implements SqlAstNode {
}

