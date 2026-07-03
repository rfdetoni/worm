package com.github.rfdetoni.worm.config;

import com.github.rfdetoni.worm.orm.registry.EntityMetadata;
import com.github.rfdetoni.worm.orm.registry.EntityRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

public class WormWarmupExecutor implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(WormWarmupExecutor.class);
    private static final UUID ZERO_UUID = UUID.fromString("00000000-0000-7000-0000-000000000000");

    private final WormProperties properties;
    private final JdbcTemplate jdbcTemplate;

    public WormWarmupExecutor(WormProperties properties, JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!properties.isWarmupEnabled()) {
            return;
        }
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

    private void warmupInsertDelete(EntityMetadata<?> metadata) {
        long start = System.currentTimeMillis();
        try {
            jdbcTemplate.execute((java.sql.Connection conn) -> {
                try (var ins = conn.prepareStatement(metadata.insertSql());
                     var del = conn.prepareStatement(
                             metadata.deleteSql() != null ? metadata.deleteSql()
                                     : "SELECT 1")) {
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
