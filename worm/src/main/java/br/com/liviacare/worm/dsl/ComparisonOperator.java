package br.com.liviacare.worm.dsl;

enum ComparisonOperator {
    EQ("="),
    NE("<>"),
    GT(">"),
    GOE(">="),
    LT("<"),
    LOE("<=");

    private final String sql;

    ComparisonOperator(String sql) {
        this.sql = sql;
    }

    String sql() {
        return sql;
    }
}

