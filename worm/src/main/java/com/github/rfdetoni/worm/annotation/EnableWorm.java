package com.github.rfdetoni.worm.annotation;

import com.github.rfdetoni.worm.config.OrmAutoConfiguration;
import com.github.rfdetoni.worm.config.TransactionConfig;
import com.github.rfdetoni.worm.config.WebMvcConfig;
import com.github.rfdetoni.worm.config.query.QueryRepositoriesAutoConfiguration;
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
        WebMvcConfig.class,
        QueryRepositoriesAutoConfiguration.class
})
public @interface EnableWorm {
}
