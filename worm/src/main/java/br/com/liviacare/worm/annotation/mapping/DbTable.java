package br.com.liviacare.worm.annotation.mapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DbTable {
    String value();
    // optional schema name (empty = no schema)
    String schema() default "";
    // optional module name used by ModuleRoutingDataSource to pick the correct DataSource
    String module() default "";
}
