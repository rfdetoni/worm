package br.com.liviacare.worm.annotation.mapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

    @Target({ElementType.TYPE, ElementType.RECORD_COMPONENT, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface OrderBy {
    String value() default "";
    Direction direction() default Direction.ASC;

    enum Direction { ASC, DESC }
}
