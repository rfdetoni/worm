package br.com.liviacare.worm.annotation.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface Active {
    /** Column name override. Defaults to "active". */
    String value() default "active";

    /**
     * Default value written on INSERT when the field has not been explicitly set
     * (e.g. primitive {@code boolean} fields default to {@code false} with Lombok builders).
     * Set to {@code false} to insert new rows as inactive by default.
     */
    boolean defaultValue() default true;
}
