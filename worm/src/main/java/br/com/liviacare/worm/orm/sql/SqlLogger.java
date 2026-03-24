package br.com.liviacare.worm.orm.sql;

import org.slf4j.Logger;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Responsible solely for formatting and emitting SQL debug logs.
 */
public final class SqlLogger {

    private SqlLogger() {}

    public static void log(Logger logger, String operation, String sql,
                           List<Object> params, long elapsedNanos) {
        if (!logger.isDebugEnabled()) return;

        final double ms = elapsedNanos / 1_000_000.0;
        logger.debug(SqlConstants.LOG_FORMAT,
                operation,
                String.format("%.3f", ms),
                format(sql, params));
    }

    public static void logBatch(Logger logger, String operation, String sql,
                                List<Object[]> params, long elapsedNanos) {
        if (!logger.isDebugEnabled()) return;

        final double ms = elapsedNanos / 1_000_000.0;
        logger.debug(SqlConstants.LOG_FORMAT,
                operation,
                String.format("%.3f", ms),
                formatBatch(sql, params));
    }

    public static String formatBatch(String sql, List<Object[]> params) {
        if (params == null || params.isEmpty()) {
            return "Batch with 0 statements";
        }
        final String formattedParams = params.stream()
                .map(p -> format(sql, Arrays.asList(p)))
                .collect(Collectors.joining("\n  "));
        return String.format("Batch with %d statements:\n  %s", params.size(), formattedParams);
    }

    public static String format(String sql, List<Object> params) {
        final String singleLineSql = sanitize(sql);
        if (params == null || params.isEmpty()) {
            return singleLineSql;
        }
        final StringBuilder sb = new StringBuilder(singleLineSql.length() + params.size() * 8);
        int paramIndex = 0;
        for (int i = 0; i < singleLineSql.length(); i++) {
            final char c = singleLineSql.charAt(i);
            if (c == '?' && paramIndex < params.size()) {
                appendParam(sb, params.get(paramIndex++));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static void appendParam(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("NULL");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof String || value instanceof UUID
                || value instanceof Enum<?> || value instanceof Instant) {
            sb.append('\'').append(sanitize(String.valueOf(value))).append('\'');
        } else {
            sb.append('\'').append(sanitize(String.valueOf(value))).append('\'');
        }
    }

    private static String sanitize(String input) {
        if (input == null || input.isEmpty()) return input;
        // Replace CR and LF with spaces, and collapse consecutive whitespace to a single space
        StringBuilder out = new StringBuilder(input.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\r' || c == '\n' || Character.isWhitespace(c)) {
                if (!lastWasSpace) {
                    out.append(' ');
                    lastWasSpace = true;
                }
            } else {
                out.append(c);
                lastWasSpace = false;
            }
        }
        return out.toString();
    }
}
