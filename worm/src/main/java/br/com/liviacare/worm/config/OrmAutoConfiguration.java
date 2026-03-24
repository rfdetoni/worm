package br.com.liviacare.worm.config;

import br.com.liviacare.worm.config.metrics.LatencyRecorder;
import br.com.liviacare.worm.config.metrics.NoOpLatencyRecorder;
import br.com.liviacare.worm.config.metrics.WormLatencyRecorder;
import br.com.liviacare.worm.orm.OrmManager;
import br.com.liviacare.worm.orm.OrmManagerLocator;
import br.com.liviacare.worm.orm.OrmOperations;
import br.com.liviacare.worm.orm.converter.ConverterRegistry;
import br.com.liviacare.worm.orm.dialect.PostgresDialect;
import br.com.liviacare.worm.orm.dialect.SqlDialect;
import br.com.liviacare.worm.orm.registry.EntityRegistry;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(WormProperties.class)
public class OrmAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SqlDialect.class)
    public SqlDialect sqlDialect() {
        SqlDialect d = new PostgresDialect();
        // ensure EntityRegistry has a dialect as early as possible to avoid metadata being built without dialect
        try {
            EntityRegistry.setSqlDialect(d);
        } catch (Throwable ignored) {
        }
        return d;
    }

    @Bean
    @ConditionalOnMissingBean(OrmOperations.class)
    public OrmOperations ormManager(
            DataSource dataSource,
            WormProperties properties,
            SqlDialect sqlDialect,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            PlatformTransactionManager transactionManager,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            LatencyRecorder latencyRecorder) {
        applyHikariPreparedStatementCacheDefaults(dataSource);
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        OrmManager manager = new OrmManager(jdbcClient, properties, sqlDialect, dataSource, transactionManager, latencyRecorder);
        OrmManagerLocator.setOrmManager(manager);
        return manager;
    }

    @Bean
    public WormWarmupExecutor wormWarmupExecutor(WormProperties properties, JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        return new WormWarmupExecutor(properties, jdbcTemplate, transactionTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(LatencyRecorder.class)
    public LatencyRecorder latencyRecorder(WormProperties properties) {
        if (properties.isMetricsEnabled()) {
            return new WormLatencyRecorder();
        } else {
            return new NoOpLatencyRecorder();
        }
    }

    private void applyHikariPreparedStatementCacheDefaults(DataSource dataSource) {
        if (!(dataSource instanceof HikariDataSource hikari)) {
            return;
        }
        // PERF: set driver-level statement cache defaults once to reduce parse/plan overhead.
        setIfAbsent(hikari, "cachePrepStmts", "true");
        // PERF: keep cache large enough to cover common ORM CRUD templates.
        setIfAbsent(hikari, "prepStmtCacheSize", "250");
        // PERF: allow long generated SQL statements to stay cacheable.
        setIfAbsent(hikari, "prepStmtCacheSqlLimit", "2048");
        // PERF: enable server-side prepared statements on drivers that support it.
        setIfAbsent(hikari, "useServerPrepStmts", "true");
    }

    private static void setIfAbsent(HikariDataSource hikari, String key, String value) {
        if (hikari.getDataSourceProperties().containsKey(key)) {
            return;
        }
        try {
            hikari.addDataSourceProperty(key, value);
        } catch (IllegalStateException ex) {
            // Hikari seals its configuration once the pool is started. If we reach here,
            // it means the DataSource was already initialized elsewhere. Best effort:
            // don't fail application startup for a non-critical performance tweak.
            // The driver/property may already be set, or cannot be changed at runtime.
            // Ignore the exception and proceed.
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent ev) {
        ApplicationContext ctx = ev.getApplicationContext();
        // Dialect has been set at bean creation time (sqlDialect()). Now wire ConverterRegistry if present.
        try {
            ConverterRegistry conv = ctx.getBean(ConverterRegistry.class);
            EntityRegistry.setConverterRegistry(conv);
        } catch (Exception ignored) {
            // no-op if ConverterRegistry not present
        }
    }
}
