package br.com.liviacare.worm.orm.sql;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.ClassUtils;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

public final class SqlExecutor {

    private static final Logger log = LoggerFactory.getLogger(SqlExecutor.class);

    private final JdbcClient jdbcClient;

    // Micrometer optional
    @Autowired(required = false)
    private MeterRegistry meterRegistry;
    private final boolean micrometerPresent = ClassUtils.isPresent("io.micrometer.core.instrument.MeterRegistry", SqlExecutor.class.getClassLoader());

    public SqlExecutor(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
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
        if (batchParams == null || batchParams.isEmpty()) return new int[0];
        // record batch size metric
        try {
            // Try to obtain DataSource from underlying JdbcOperations
            javax.sql.DataSource ds = null;
            try {
                java.lang.reflect.Method m = jdbcClient.getClass().getMethod("getJdbcOperations");
                Object ops = m.invoke(jdbcClient);
                java.lang.reflect.Method gds = ops.getClass().getMethod("getDataSource");
                Object dsObj = gds.invoke(ops);
                if (dsObj instanceof javax.sql.DataSource) ds = (javax.sql.DataSource) dsObj;
            } catch (Throwable ignored) {
                // fallback to null
            }

            if (ds == null) {
                // Fallback: execute per-row via jdbcClient (less efficient)
                int[] results = new int[batchParams.size()];
                for (int i = 0; i < batchParams.size(); i++) {
                    Object[] p = batchParams.get(i);
                    int r = jdbcClient.sql(sql).params(Arrays.asList(p)).update();
                    results[i] = r;
                }
                return results;
            }

            // before executing, emit batch size metric if possible
            try {
                // attempt to derive entityName from SQL (best-effort: use first table name token)
                String entityName = "unknown";
                try {
                    String s = sql.trim();
                    String[] parts = s.split("[\\s,]+");
                    if (parts.length >= 3 && parts[0].equalsIgnoreCase("INSERT") && parts[1].equalsIgnoreCase("INTO")) {
                        entityName = parts[2];
                      } else if (parts.length >= 2 && parts[0].equalsIgnoreCase("UPDATE")) {
                        entityName = parts[1];
                      }
                } catch (Exception ignored) {}
                recordBatchSize("batch", entityName, batchParams.size());
            } catch (Exception ignored) {}

            try (java.sql.Connection conn = ds.getConnection();
                 java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                conn.setAutoCommit(true);
                for (Object[] params : batchParams) {
                    for (int i = 0; i < params.length; i++) {
                        ps.setObject(i + 1, params[i]);
                    }
                    ps.addBatch();
                }
                return ps.executeBatch();
            }
        } catch (Throwable t) {
            throw new RuntimeException("Batch execution failed", t);
        }
    }
}
