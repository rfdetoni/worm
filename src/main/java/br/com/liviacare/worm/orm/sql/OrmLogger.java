package br.com.liviacare.worm.orm.sql;

import org.slf4j.Logger;

import java.util.List;
import java.util.function.Supplier;

/**
 * Centralized ORM operation logger with conditional SQL formatting.
 */
public final class OrmLogger {
    private static final int DEFAULT_ASYNC_SQL_LOG_QUEUE_SIZE = 8192;

    private final Logger log;
    private final Logger externalLog;
    private final boolean asyncSqlLogging;
    private final AsyncSqlLogDispatcher dispatcher;

    public OrmLogger(Logger log) {
        this(log, null, true, DEFAULT_ASYNC_SQL_LOG_QUEUE_SIZE);
    }

    public OrmLogger(Logger log, Logger externalLog) {
        this(log, externalLog, true, DEFAULT_ASYNC_SQL_LOG_QUEUE_SIZE);
    }

    public OrmLogger(Logger log, Logger externalLog, boolean asyncSqlLogging, int asyncSqlLogQueueSize) {
        this.log = log;
        this.externalLog = externalLog;
        this.asyncSqlLogging = asyncSqlLogging;
        this.dispatcher = asyncSqlLogging ? new AsyncSqlLogDispatcher(asyncSqlLogQueueSize) : null;
    }

    public boolean isDebugEnabled() {
        return log.isDebugEnabled() || (externalLog != null && externalLog.isDebugEnabled());
    }

    public <T> T logAndExecute(String operation, String sql, List<Object> params, Supplier<T> action) {
        boolean debugMain = log.isDebugEnabled();
        boolean debugExt = externalLog != null && externalLog.isDebugEnabled();
        if (!debugMain && !debugExt) {
            return action.get();
        }
        // Format + log only ONCE, after execution, with elapsed time.
        // Formatting twice (before + after) was the cause of paginated-query slowness.
        long start = System.nanoTime();
        try {
            T result = action.get();
            long elapsed = System.nanoTime() - start;
            if (debugMain) logSql(log, operation, sql, params, elapsed);
            if (debugExt)  logSql(externalLog, operation, sql, params, elapsed);
            return result;
        } catch (Throwable t) {
            long elapsed = System.nanoTime() - start;
            if (debugMain) logSql(log, operation + "-FAILED", sql, params, elapsed);
            if (debugExt)  logSql(externalLog, operation + "-FAILED", sql, params, elapsed);
            throw t;
        }
    }

    public void logAndExecute(String operation, String sql, List<Object> params, Runnable action) {
        logAndExecute(operation, sql, params, () -> { action.run(); return null; });
    }

    public <T> T logBatchAndExecute(String operation, String sql, List<Object[]> params, Supplier<T> action) {
        boolean debugMain = log.isDebugEnabled();
        boolean debugExt = externalLog != null && externalLog.isDebugEnabled();
        if (!debugMain && !debugExt) {
            return action.get();
        }
        long start = System.nanoTime();
        try {
            T result = action.get();
            long elapsed = System.nanoTime() - start;
            if (debugMain) logBatch(log, operation, sql, params, elapsed);
            if (debugExt)  logBatch(externalLog, operation, sql, params, elapsed);
            return result;
        } catch (Throwable t) {
            long elapsed = System.nanoTime() - start;
            if (debugMain) logBatch(log, operation + "-FAILED", sql, params, elapsed);
            if (debugExt)  logBatch(externalLog, operation + "-FAILED", sql, params, elapsed);
            throw t;
        }
    }

    public void logBatchAndExecute(String operation, String sql, List<Object[]> params, Runnable action) {
        logBatchAndExecute(operation, sql, params, () -> { action.run(); return null; });
    }

    private void logSql(Logger target, String operation, String sql, List<Object> params, long elapsedNanos) {
        if (!asyncSqlLogging || dispatcher == null) {
            SqlLogger.log(target, operation, sql, params, elapsedNanos);
            return;
        }
        dispatcher.submit(() -> SqlLogger.log(target, operation, sql, params, elapsedNanos));
    }

    private void logBatch(Logger target, String operation, String sql, List<Object[]> params, long elapsedNanos) {
        if (!asyncSqlLogging || dispatcher == null) {
            SqlLogger.logBatch(target, operation, sql, params, elapsedNanos);
            return;
        }
        dispatcher.submit(() -> SqlLogger.logBatch(target, operation, sql, params, elapsedNanos));
    }
}
