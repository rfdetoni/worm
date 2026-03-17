package br.com.liviacare.worm.config;

import br.com.liviacare.worm.orm.converter.ConverterRegistry;
import br.com.liviacare.worm.orm.dialect.PostgresDialect;
import br.com.liviacare.worm.orm.dialect.SqlDialect;
import br.com.liviacare.worm.orm.registry.EntityRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

@Configuration
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

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent ev) {
        ApplicationContext ctx = ev.getApplicationContext();
        // Dialect has been set at bean creation time (sqlDialect()). Now wire ConverterRegistry if present.
        try {
            ConverterRegistry conv = ctx.getBean(ConverterRegistry.class);
            if (conv != null) EntityRegistry.setConverterRegistry(conv);
        } catch (Exception ignored) {
            // no-op if ConverterRegistry not present
        }
    }
}
