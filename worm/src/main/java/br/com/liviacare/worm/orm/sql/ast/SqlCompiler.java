package br.com.liviacare.worm.orm.sql.ast;

/**
 * Visitor that compiles SQL AST nodes into final SQL strings.
 */
public final class SqlCompiler implements SqlAstVisitor<String> {

    @Override
    public String visitSelect(SelectNode node) {
        StringBuilder sql = new StringBuilder(node.selectFrom());
        appendJoins(sql, node.joins());
        appendWhere(sql, node.whereClause());
        append(sql, node.groupByClause());
        append(sql, node.orderByClause());
        append(sql, node.paginationClause());
        return sql.toString();
    }

    private static void appendJoins(StringBuilder sql, java.util.List<JoinNode> joins) {
        if (joins == null || joins.isEmpty()) {
            return;
        }
        for (JoinNode join : joins) {
            append(sql, join.type() + " JOIN " + join.table() + " " + join.alias() + " ON " + join.onCondition().expression());
        }
    }

    private static void appendWhere(StringBuilder sql, WhereNode where) {
        if (where == null || where.conditions() == null || where.conditions().isEmpty()) {
            return;
        }
        StringBuilder clause = new StringBuilder("WHERE ");
        for (int i = 0; i < where.conditions().size(); i++) {
            if (i > 0) {
                clause.append(where.separator());
            }
            clause.append(where.conditions().get(i).expression());
        }
        append(sql, clause.toString());
    }

    private static void append(StringBuilder sb, String fragment) {
        if (fragment == null || fragment.isBlank()) {
            return;
        }
        if (sb.length() > 0 && !Character.isWhitespace(sb.charAt(sb.length() - 1))
                && !Character.isWhitespace(fragment.charAt(0))) {
            sb.append(' ');
        }
        sb.append(fragment);
    }
}

