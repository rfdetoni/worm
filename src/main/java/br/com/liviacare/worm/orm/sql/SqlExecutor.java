package br.com.liviacare.worm.orm.sql;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.ClassUtils;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

public final class SqlExecutor {

    private static final Logger log = LoggerFactory.getLogger(SqlExecutor.class);

    private final JdbcClient jdbcClient;
    private final javax.sql.DataSource dataSource;
    // Cache da resolução por reflexão — resolvido apenas uma vez via DCL
    private volatile javax.sql.DataSource resolvedDataSource;
    private volatile JdbcTemplate jdbcTemplate;

    // Micrometer optional
    @Autowired(required = false)
    private MeterRegistry meterRegistry;
    private final boolean micrometerPresent = ClassUtils.isPresent("io.micrometer.core.instrument.MeterRegistry", SqlExecutor.class.getClassLoader());

    public SqlExecutor(JdbcClient jdbcClient) {
        this(jdbcClient, null);
    }

    public SqlExecutor(JdbcClient jdbcClient, javax.sql.DataSource dataSource) {
        this.jdbcClient = jdbcClient;
        this.dataSource = dataSource;
    }

    public JdbcClient client() {
        return jdbcClient;
    }

    /**
     * Executes {@code operation} and, when DEBUG is enabled, measures its
     * wall-clock time and logs the interpolated SQL via {@link SqlLogger}.
     *
     * @param label  short label for the log line, e.g. {@code "INSERT"}
     * @param sql    the raw SQL with {@code ?} placeholders
     * @param params the bound parameters in bind order
     * @param op     the actual JDBC operation to run
     * @param <R>    the return type of the operation
     * @return whatever {@code op} returns
     */
    // Backwards-compatible logging-enabled execute (kept for other callers)
    public <R> R execute(String label, String sql, List<Object> params, Supplier<R> op) {
        if (log.isDebugEnabled()) {
            final long start = System.nanoTime();
            try {
                final R result = op.get();
                SqlLogger.log(log, label, sql, params, System.nanoTime() - start);
                return result;
            } catch (RuntimeException ex) {
                SqlLogger.log(log, label + "/ERROR", sql, params, System.nanoTime() - start);
                throw ex;
            }
        }
        return op.get();
    }

    // Thin convenience overloads used by OrmManager when it manages logging/timing itself
    public <R> R execute(Supplier<R> op) {
        return op.get();
    }

    public void execute(Runnable op) {
        op.run();
    }

    public void executeUpdate(Runnable op) {
        op.run();
    }

    public void recordBatchSize(String operation, String entityName, int size) {
        if (!micrometerPresent || meterRegistry == null) return;
        DistributionSummary.builder("orm.batch.size").tag("operation", operation).tag("entity", entityName).register(meterRegistry).record(size);
    }

    public void incrementOptimisticLockFailure(String entityName) {
        if (!micrometerPresent || meterRegistry == null) return;
        Counter.builder("orm.optimistic.lock.failures").tag("entity", entityName).register(meterRegistry).increment();
    }

    public <R> R timeAndRecord(String operation, String entityName, Supplier<R> op) {
        if (!micrometerPresent || meterRegistry == null) {
            return op.get();
        }
        Timer.Sample sample = Timer.start(meterRegistry);
        R result = op.get();
        long elapsed = System.nanoTime(); // we'll rely on sample.stop below
        sample.stop(Timer.builder("orm.query.duration").tag("operation", operation).tag("entity", entityName).register(meterRegistry));
        if (result instanceof List<?> list) {
            DistributionSummary summary = DistributionSummary.builder("orm.query.rows").tag("operation", operation).tag("entity", entityName).register(meterRegistry);
            summary.record(list.size());
        }
        return result;
    }

    public int[] executeBatch(String sql, List<Object[]> batchParams) {
        return executeBatch(sql, batchParams, null);
    }

    public int[] executeBatch(String sql, List<Object[]> batchParams, String entityName) {
        if (batchParams == null || batchParams.isEmpty()) return new int[0];
        if (entityName != null) {
            recordBatchSize("batch", entityName, batchParams.size());
        }

        try {
            javax.sql.DataSource ds = resolveDataSource();
            if (ds == null) {
                int[] results = new int[batchParams.size()];
                for (int i = 0; i < batchParams.size(); i++) {
                    results[i] = jdbcClient.sql(sql).params(Arrays.asList(batchParams.get(i))).update();
                }
                return results;
            }

            return getOrCreateJdbcTemplate(ds).batchUpdate(sql, batchParams);
        } catch (Throwable t) {
            throw new RuntimeException("Batch execution failed", t);
        }
    }

    public javax.sql.DataSource dataSourceOrNull() {
        return resolveDataSource();
    }

    private JdbcTemplate getOrCreateJdbcTemplate(javax.sql.DataSource ds) {
        JdbcTemplate jt = jdbcTemplate;
        if (jt != null) {
            return jt;
        }
        synchronized (this) {
            jt = jdbcTemplate;
            if (jt == null) {
                jt = new JdbcTemplate(ds);
                jdbcTemplate = jt;
            }
        }
        return jt;
    }

    private javax.sql.DataSource resolveDataSource() {
        // Primeiro: usar o dataSource injetado diretamente
        if (dataSource != null) return dataSource;

        // Segundo: checar cache volatile
        javax.sql.DataSource cached = resolvedDataSource;
        if (cached != null) return cached;

        // Terceiro: resolver por reflexão UMA única vez via DCL
        synchronized (this) {
            if (resolvedDataSource != null) return resolvedDataSource;
            try {
                java.lang.reflect.Method m = jdbcClient.getClass().getMethod("getJdbcOperations");
                Object ops = m.invoke(jdbcClient);
                java.lang.reflect.Method gds = ops.getClass().getMethod("getDataSource");
                Object dsObj = gds.invoke(ops);
                if (dsObj instanceof javax.sql.DataSource ds) {
                    resolvedDataSource = ds;
                    return ds;
                }
            } catch (Throwable ignored) {}
            return null;
        }
    }
}
