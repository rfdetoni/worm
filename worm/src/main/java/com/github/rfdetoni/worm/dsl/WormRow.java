package com.github.rfdetoni.worm.dsl;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * Positional projection row.
 */
public final class WormRow {

    private final Object[] values;

    WormRow(Object[] values) {
        this.values = values;
    }

    static WormRow from(ResultSet rs, int columns) throws SQLException {
        Object[] row = new Object[columns];
        for (int i = 0; i < columns; i++) {
            row[i] = rs.getObject(i + 1);
        }
        return new WormRow(row);
    }

    public int size() {
        return values.length;
    }

    public Object get(int index) {
        return values[index];
    }

    public <T> T get(int index, Class<T> type) {
        Object value = values[index];
        return value == null ? null : type.cast(value);
    }

    public Object[] toArray() {
        return Arrays.copyOf(values, values.length);
    }
}

