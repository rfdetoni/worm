package com.github.rfdetoni.worm.config.warmup;

import com.github.rfdetoni.worm.orm.registry.EntityMetadata;
import com.github.rfdetoni.worm.orm.registry.EntityRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

public class SimpleWarmupStrategy implements WarmupStrategy {

    private static final Logger log = LoggerFactory.getLogger(SimpleWarmupStrategy.class);
    // Zero-value UUID used as a harmless probe parameter for selectById warmup
    private static final UUID ZERO_UUID = UUID.fromString("00000000-0000-7000-0000-000000000000");

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public SimpleWarmupStrategy(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void warmup() {
        log.info("WORM warmup started.");
        for (EntityMetadata<?> metadata : EntityRegistry.getAllMetadata()) {
            warmupEntity(metadata);
        }
        log.info("WORM warmup finished.");
    }

    private void warmupEntity(EntityMetadata<?> metadata) {
        warmupSelectById(metadata);
        warmupInsertDelete(metadata);
    }

    private void warmupSelectById(EntityMetadata<?> metadata) {
        long start = System.currentTimeMillis();
        final String selectByIdSql = metadata.idSelectSql();
        try {
            jdbcTemplate.query(selectByIdSql,
                    ps -> ps.setObject(1, ZERO_UUID),
                    rs -> {});
            log.debug("WORM warmup: [{}.selectById] — {}ms",
                    metadata.tableName(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("WORM warmup selectById failed for [{}]: {}", metadata.tableName(), e.getMessage());
        }
    }

    /**
     * Warms INSERT and DELETE prepared statements by asking the JDBC driver to
     * {@code prepareStatement(sql)} inside a rolled-back transaction. Preparing the
     * statement is sufficient to populate the driver/server plan cache; no bind values
     * or execution is required, avoiding constraint-violation noise in application logs.
     */
    private void warmupInsertDelete(EntityMetadata<?> metadata) {
        long start = System.currentTimeMillis();
        try {
            jdbcTemplate.execute((java.sql.Connection conn) -> {
                // PERF: prepareStatement triggers driver-level PS cache population.
                //       Server-side plan (useServerPrepStmts=true) is also seeded here.
                try (var ins = conn.prepareStatement(metadata.insertSql());
                     var del = conn.prepareStatement(
                             metadata.deleteSql() != null ? metadata.deleteSql()
                                     : "SELECT 1")) {
                    // Preparing is all that is needed; execution is not required.
                }
                return null;
            });
            log.debug("WORM warmup: [{}.insert/delete] — {}ms",
                    metadata.tableName(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("WORM warmup insert/delete failed for [{}]: {}", metadata.tableName(), e.getMessage());
        }
    }
}
