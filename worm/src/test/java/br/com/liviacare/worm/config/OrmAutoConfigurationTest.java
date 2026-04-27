package br.com.liviacare.worm.config;

import br.com.liviacare.worm.orm.dialect.MySQLDialect;
import br.com.liviacare.worm.orm.dialect.SqlDialect;
import br.com.liviacare.worm.orm.registry.EntityRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OrmAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OrmAutoConfiguration.class))
            .withUserConfiguration(CustomMySqlDialectConfig.class)
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));

    @Test
    void customSqlDialectUpdatesEntityRegistry() throws Exception {
        SqlDialect previousDialect = currentDialect();
        try {
            contextRunner.run(context -> {
                assertThat(context).hasSingleBean(SqlDialect.class);
                assertThat(context.getBean(SqlDialect.class)).isInstanceOf(MySQLDialect.class);
                assertThat(currentDialect()).isInstanceOf(MySQLDialect.class);
            });
        } finally {
            EntityRegistry.setSqlDialect(previousDialect);
        }
    }

    private static SqlDialect currentDialect() throws Exception {
        Field field = EntityRegistry.class.getDeclaredField("sqlDialect");
        field.setAccessible(true);
        return (SqlDialect) field.get(null);
    }

    @Configuration
    static class CustomMySqlDialectConfig {
        @Bean("mySqlDialect")
        SqlDialect mySqlDialect() {
            return new MySQLDialect();
        }
    }
}
