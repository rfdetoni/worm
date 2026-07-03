package com.github.rfdetoni.worm.orm.sql.ast;

/** Marker interface for SQL AST nodes. */
public sealed interface SqlAstNode permits SelectNode, JoinNode, WhereNode, ConditionNode {
}

