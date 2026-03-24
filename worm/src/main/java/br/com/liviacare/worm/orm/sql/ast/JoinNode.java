package br.com.liviacare.worm.orm.sql.ast;

/**
 * Structured JOIN clause.
 */
public record JoinNode(String type, String table, String alias, ConditionNode onCondition) implements SqlAstNode {
}

