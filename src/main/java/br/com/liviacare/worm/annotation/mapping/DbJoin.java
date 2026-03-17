package br.com.liviacare.worm.annotation.mapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface DbJoin {
    /**
     * Legacy single-value form (kept for backward compatibility). Prefer using named properties.
     */
    String value() default "";

    /** Name of the joined table (ex: "document_types") */
    String table() default "";

    /** Alias to use for the joined table (ex: "documentTypes") */
    String alias() default "";

    /** Join predicate (ex: "documentTypes.id = document_templates.document_type_id") */
    String on() default "";

    /**
     * Local FK column on the main entity table.
     * Example: localColumn = "document_type_id".
     * When set (and {@link #on()} is blank), ON is inferred as:
     * {@code <alias>.<referencedColumn> = a.<localColumn>}.
     */
    String localColumn() default "";

    /**
     * Referenced PK/UK column on the joined table.
     * Defaults to {@code id}.
     */
    String referencedColumn() default "id";

    /**
     * Alias for {@link #referencedColumn()} to mirror JPA vocabulary.
     * When both are provided, {@code targetColumn} takes precedence.
     */
    String targetColumn() default "";

    /**
     * FK column on the joined table for one-to-many joins (collection fields).
     * When set (and {@link #on()} is blank), ON is inferred as:
     * {@code <alias>.<mappedBy> = a.id}.
     */
    String mappedBy() default "";

    /** Type of join to use when generating SQL */
    Type type() default Type.INNER;

    /** Supported join types */
    enum Type {
        INNER,
        LEFT,
        RIGHT,
        CROSS
    }
}
