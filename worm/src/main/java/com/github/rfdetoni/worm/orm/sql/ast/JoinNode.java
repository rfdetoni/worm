package com.github.rfdetoni.worm.orm.sql.ast;

/**
 * Structured JOIN clause.
 */
public record JoinNode(String type, String table, String alias, ConditionNode onCondition) implements SqlAstNode {
}

