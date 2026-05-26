package com.github.rfdetoni.worm.dsl;

public enum JoinType {
    INNER("INNER"),
    LEFT("LEFT"),
    RIGHT("RIGHT"),
    FULL("FULL");

    private final String sql;

    JoinType(String sql) {
        this.sql = sql;
    }

    String sql() {
        return sql;
    }
}

