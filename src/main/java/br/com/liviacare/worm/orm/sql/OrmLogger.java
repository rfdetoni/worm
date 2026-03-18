package br.com.liviacare.worm.orm.sql;

import org.slf4j.Logger;

import java.util.List;
import java.util.function.Supplier;

/**
 * Centralized ORM operation logger with conditional SQL formatting.
 */
public final class OrmLogger {

    private final Logger log;
    private final Logger externalLog;

    public OrmLogger(Logger log) {
        this(log, null);
    }

    public OrmLogger(Logger log, Logger externalLog) {
        this.log = log;
        this.externalLog = externalLog;
    }

    public <T> T logAndExecute(String operation, String sql, List<Object> params, Supplier<T> action) {
        boolean debugMain = log.isDebugEnabled();
        boolean debugExt  = externalLog != null && externalLog.isDebugEnabled();

        if (debugMain || debugExt) {
            String interpolated = SqlLogger.format(sql, params);
            if (debugMain)  log.debug("[WORM(Weightless ORM)] [{}] {}", operation, interpolated);
            if (debugExt)   externalLog.debug("[WORM(Weightless ORM)] [{}] {}", operation, interpolated);
        }

        long start = System.nanoTime();
        try {
            T result = action.get();
            if (debugMain || debugExt) {
                long elapsed = System.nanoTime() - start;
                if (debugMain) SqlLogger.log(log, operation, sql, params, elapsed);
                if (debugExt)  SqlLogger.log(externalLog, operation, sql, params, elapsed);
            }
            return result;
        } catch (Throwable t) {
            if (debugMain || debugExt) {
                long elapsed = System.nanoTime() - start;
                if (debugMain) SqlLogger.log(log, operation + "-FAILED", sql, params, elapsed);
                if (debugExt)  SqlLogger.log(externalLog, operation + "-FAILED", sql, params, elapsed);
            }
            throw t;
        }
    }

    public void logAndExecute(String operation, String sql, List<Object> params, Runnable action) {
        logAndExecute(operation, sql, params, () -> { action.run(); return null; });
    }

    public <T> T logBatchAndExecute(String operation, String sql, List<Object[]> params, Supplier<T> action) {
        boolean debugMain = log.isDebugEnabled();
        boolean debugExt  = externalLog != null && externalLog.isDebugEnabled();

        if (debugMain || debugExt) {
            String interpolatedBatch = SqlLogger.formatBatch(sql, params);
            if (debugMain) log.debug("[WORM(Weightless ORM)] [{}] {}", operation, interpolatedBatch);
            if (debugExt)  externalLog.debug("[WORM(Weightless ORM)] [{}] {}", operation, interpolatedBatch);
        }

        long start = System.nanoTime();
        try {
            T result = action.get();
            if (debugMain || debugExt) {
                long elapsed = System.nanoTime() - start;
                if (debugMain) SqlLogger.logBatch(log, operation, sql, params, elapsed);
                if (debugExt)  SqlLogger.logBatch(externalLog, operation, sql, params, elapsed);
            }
            return result;
        } catch (Throwable t) {
            if (debugMain || debugExt) {
                long elapsed = System.nanoTime() - start;
                if (debugMain) SqlLogger.logBatch(log, operation + "-FAILED", sql, params, elapsed);
                if (debugExt)  SqlLogger.logBatch(externalLog, operation + "-FAILED", sql, params, elapsed);
            }
            throw t;
        }
    }

    public void logBatchAndExecute(String operation, String sql, List<Object[]> params, Runnable action) {
        logBatchAndExecute(operation, sql, params, () -> { action.run(); return null; });
    }
}
