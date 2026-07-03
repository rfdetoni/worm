package com.github.rfdetoni.worm.orm.sql.ast;

import java.util.List;

/**
 * WHERE clause composed of normalized condition nodes joined by a logical separator.
 */
public record WhereNode(List<ConditionNode> conditions, String separator) implements SqlAstNode {
}

