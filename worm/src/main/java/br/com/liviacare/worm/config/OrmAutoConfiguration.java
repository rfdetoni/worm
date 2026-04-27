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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
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
    public static BeanPostProcessor sqlDialectEntityRegistryPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof SqlDialect dialect) {
                    try {
                        EntityRegistry.setSqlDialect(dialect);
                    } catch (Throwable ignored) {
                    }
                }
                return bean;
            }
        };
    }

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
            ApplicationContext applicationContext,
            ObjectProvider<DataSource> dataSourceProvider,
            WormProperties properties,
            SqlDialect sqlDialect,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            PlatformTransactionManager transactionManager,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            LatencyRecorder latencyRecorder) {
        DataSource dataSource = resolveDataSource(applicationContext, dataSourceProvider);

        applyHikariPreparedStatementCacheDefaults(dataSource);
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        OrmManager manager = new OrmManager(jdbcClient, properties, sqlDialect, dataSource, transactionManager, latencyRecorder);
        OrmManagerLocator.setOrmManager(manager);
        return manager;
    }

    /**
     * Resolve a single DataSource to be used by WORM. Preference order:
     * 1) bean named 'tenantRoutingDataSource'
     * 2) the single DataSource in the context
     * 3) ObjectProvider.getIfAvailable() (returns primary if defined)
     * Throws an informative IllegalStateException when ambiguous or missing.
     */
    private DataSource resolveDataSource(ApplicationContext applicationContext, ObjectProvider<DataSource> dataSourceProvider) {
        DataSource dataSource;
        if (applicationContext.containsBean("tenantRoutingDataSource")) {
            dataSource = applicationContext.getBean("tenantRoutingDataSource", DataSource.class);
        } else {
            var dss = applicationContext.getBeansOfType(DataSource.class);
            if (dss.size() == 1) {
                dataSource = dss.values().iterator().next();
            } else {
                dataSource = dataSourceProvider.getIfAvailable();
                if (dataSource == null) {
                    throw new IllegalStateException(
                            "[WORM(Weightless ORM)] Ambiguous DataSource beans found. " +
                                    "When multiple DataSource beans exist, expose a single routing DataSource named 'tenantRoutingDataSource' or mark exactly one DataSource as @Primary, or define your own OrmOperations bean.");
                }
            }
        }

        return dataSource;
    }

    @Bean
    @ConditionalOnMissingBean(JdbcTemplate.class)
    public JdbcTemplate jdbcTemplate(ApplicationContext applicationContext, ObjectProvider<DataSource> dataSourceProvider) {
        DataSource ds = resolveDataSource(applicationContext, dataSourceProvider);
        return new JdbcTemplate(ds);
    }

    @Bean
    @ConditionalOnMissingBean(TransactionTemplate.class)
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
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
