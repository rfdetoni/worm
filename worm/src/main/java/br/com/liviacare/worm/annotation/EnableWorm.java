package br.com.liviacare.worm.annotation;

import br.com.liviacare.worm.config.OrmAutoConfiguration;
import br.com.liviacare.worm.config.TransactionConfig;
import br.com.liviacare.worm.config.query.QueryRepositoriesAutoConfiguration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * Enables WORM infrastructure in applications that prefer explicit opt-in.
 * <p>
 * Usage:
 * <pre>
 * {@code @SpringBootApplication}
 * {@code @EnableWorm}
 * public class Application { }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import({
        OrmAutoConfiguration.class,
        TransactionConfig.class,
        QueryRepositoriesAutoConfiguration.class
})
public @interface EnableWorm {
}

