package com.github.rfdetoni.worm.config;

import com.github.rfdetoni.worm.config.warmup.NoOpWarmupStrategy;
import com.github.rfdetoni.worm.config.warmup.SimpleWarmupStrategy;
import com.github.rfdetoni.worm.config.warmup.WarmupStrategy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public class WormWarmupExecutor implements ApplicationListener<ApplicationReadyEvent> {

    private final WarmupStrategy warmupStrategy;

    public WormWarmupExecutor(WormProperties properties, JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        if (properties.isWarmupEnabled()) {
            this.warmupStrategy = new SimpleWarmupStrategy(jdbcTemplate, transactionTemplate);
        } else {
            this.warmupStrategy = new NoOpWarmupStrategy();
        }
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        warmupStrategy.warmup();
    }
}
