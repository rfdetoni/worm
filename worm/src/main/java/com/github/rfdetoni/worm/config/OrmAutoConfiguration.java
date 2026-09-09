package com.github.rfdetoni.worm.config;

import com.github.rfdetoni.worm.config.metrics.LatencyRecorder;
import com.github.rfdetoni.worm.config.metrics.NoOpLatencyRecorder;
import com.github.rfdetoni.worm.config.metrics.WormLatencyRecorder;
import com.github.rfdetoni.worm.orm.OrmManager;
import com.github.rfdetoni.worm.orm.OrmManagerLocator;
import com.github.rfdetoni.worm.orm.OrmOperations;
import com.github.rfdetoni.worm.orm.converter.ConverterRegistry;
import com.github.rfdetoni.worm.orm.dialect.PostgresDialect;
import com.github.rfdetoni.worm.orm.dialect.SqlDialect;
import com.github.rfdetoni.worm.orm.registry.EntityRegistry;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.ObjectProvider;
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
        SqlDialect dialect = new PostgresDialect();
        try {
            EntityRegistry.setSqlDialect(dialect);
        } catch (Throwable ignored) {
            // Best effort: metadata can be initialized later by the ORM manager.
        }
        return dialect;
    }

    @Bean
    @ConditionalOnMissingBean(OrmOperations.class)
    public OrmOperations ormManager(
            ApplicationContext applicationContext,
            ObjectProvider<DataSource> dataSourceProvider,
            WormProperties properties,
            SqlDialect sqlDialect,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            PlatformTransactionManager transactionManager,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            LatencyRecorder latencyRecorder) {
        DataSource dataSource = resolveDataSource(applicationContext, dataSourceProvider);

        configureQueryPlanCaches(properties.getQueryPlanCacheMaxEntries());
        applyHikariPreparedStatementCacheDefaults(dataSource);
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        OrmManager manager = new OrmManager(
                jdbcClient,
                properties,
                sqlDialect,
                dataSource,
                transactionManager,
                latencyRecorder
        );
        OrmManagerLocator.setOrmManager(manager);
        return manager;
    }

    private static void configureQueryPlanCaches(int maxEntries) {
        com.github.rfdetoni.worm.orm.sql.QueryPlanCache.configureMaxEntries(maxEntries);
        com.github.rfdetoni.worm.dsl.QueryPlanCache.configureMaxEntries(maxEntries);
    }

    private DataSource resolveDataSource(ApplicationContext applicationContext,
                                         ObjectProvider<DataSource> dataSourceProvider) {
        DataSource dataSource;
        if (applicationContext.containsBean("tenantRoutingDataSource")) {
            dataSource = applicationContext.getBean("tenantRoutingDataSource", DataSource.class);
        } else {
            var dataSources = applicationContext.getBeansOfType(DataSource.class);
            if (dataSources.size() == 1) {
                dataSource = dataSources.values().iterator().next();
            } else {
                dataSource = dataSourceProvider.getIfAvailable();
                if (dataSource == null) {
                    throw new IllegalStateException(
                            "[WORM(Weightless ORM)] Ambiguous DataSource beans found. " +
                                    "When multiple DataSource beans exist, expose a single routing DataSource " +
                                    "named 'tenantRoutingDataSource' or mark exactly one DataSource as @Primary, " +
                                    "or define your own OrmOperations bean."
                    );
                }
            }
        }

        return dataSource;
    }

    @Bean
    @ConditionalOnMissingBean(JdbcTemplate.class)
    public JdbcTemplate jdbcTemplate(ApplicationContext applicationContext,
                                     ObjectProvider<DataSource> dataSourceProvider) {
        DataSource dataSource = resolveDataSource(applicationContext, dataSourceProvider);
        return new JdbcTemplate(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean(TransactionTemplate.class)
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    public WormWarmupExecutor wormWarmupExecutor(WormProperties properties,
                                                 JdbcTemplate jdbcTemplate,
                                                 TransactionTemplate transactionTemplate) {
        return new WormWarmupExecutor(properties, jdbcTemplate, transactionTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(LatencyRecorder.class)
    public LatencyRecorder latencyRecorder(WormProperties properties) {
        if (properties.isMetricsEnabled()) {
            return new WormLatencyRecorder();
        }
        return new NoOpLatencyRecorder();
    }

    private void applyHikariPreparedStatementCacheDefaults(DataSource dataSource) {
        if (!(dataSource instanceof HikariDataSource hikari)) {
            return;
        }
        setIfAbsent(hikari, "cachePrepStmts", "true");
        setIfAbsent(hikari, "prepStmtCacheSize", "250");
        setIfAbsent(hikari, "prepStmtCacheSqlLimit", "2048");
        setIfAbsent(hikari, "useServerPrepStmts", "true");
    }

    private static void setIfAbsent(HikariDataSource hikari, String key, String value) {
        if (hikari.getDataSourceProperties().containsKey(key)) {
            return;
        }
        try {
            hikari.addDataSourceProperty(key, value);
        } catch (IllegalStateException ignored) {
            // Hikari seals configuration after pool startup. These are best-effort defaults.
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        ApplicationContext context = event.getApplicationContext();
        try {
            ConverterRegistry converterRegistry = context.getBean(ConverterRegistry.class);
            EntityRegistry.setConverterRegistry(converterRegistry);
        } catch (Exception ignored) {
            // ConverterRegistry is optional.
        }
    }
}
