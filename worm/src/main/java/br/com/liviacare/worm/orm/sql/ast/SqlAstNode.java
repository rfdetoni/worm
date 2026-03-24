package br.com.liviacare.worm.orm.sql.ast;

/** Marker interface for SQL AST nodes. */
public sealed interface SqlAstNode permits SelectNode, JoinNode, WhereNode, ConditionNode {
}

