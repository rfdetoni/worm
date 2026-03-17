package br.com.liviacare.worm.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
public class TransactionConfig {
    private static final Logger log = LoggerFactory.getLogger(TransactionConfig.class);

    /**
     * Transaction manager bean.
     * When multitenancy is enabled (ljf.multitenancy.enabled=true), uses tenantRoutingDataSource.
     * When multitenancy is disabled, falls back to any available DataSource.
     * 
     * We use @ConditionalOnMissingBean(PlatformTransactionManager.class) to ensure that if JPA
     * is present, its JpaTransactionManager (which is also a PlatformTransactionManager) 
     * takes precedence.
     */
    @Bean(name = "transactionManager")
    @ConditionalOnMissingBean(PlatformTransactionManager.class)
    public PlatformTransactionManager transactionManager(
            ApplicationContext applicationContext,
            ObjectProvider<DataSource> fallbackDsProvider) {

        DataSource ds = null;
        if (applicationContext.containsBean("tenantRoutingDataSource")) {
            ds = applicationContext.getBean("tenantRoutingDataSource", DataSource.class);
            log.info("[WORM(Weightless ORM)] Creating transactionManager backed by tenantRoutingDataSource");
            return new DataSourceTransactionManager(ds);
        }

        ds = fallbackDsProvider.getIfAvailable();
        if (ds != null) {
            log.info("[WORM(Weightless ORM)] Creating transactionManager backed by fallback DataSource: {}", ds.getClass().getSimpleName());
            return new DataSourceTransactionManager(ds);
        }

        throw new IllegalStateException("[WORM(Weightless ORM)] No DataSource bean available to create transactionManager. " +
                "Ensure at least one DataSource is configured in the application.");
    }
}
