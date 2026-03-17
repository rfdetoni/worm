package br.com.liviacare.worm.annotation.mapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface DbColumn {
    String value();

    /**
     * Optional SQL expression to be used in SELECT projection instead of the plain table column.
     * Example: address->>'state'
     * If provided, the ORM will emit: <expr> AS <alias> where alias is the annotation value or the field/component name.
     */
    String expr() default "";

    /**
     * If true, the column will be treated as a JSON/JSONB column.
     */
    boolean json() default false;
}
