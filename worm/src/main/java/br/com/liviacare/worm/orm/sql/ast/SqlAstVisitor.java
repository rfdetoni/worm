package br.com.liviacare.worm.orm.sql.ast;

/** Visitor for compiling SQL AST nodes. */
public interface SqlAstVisitor<R> {
    R visitSelect(SelectNode node);
}

