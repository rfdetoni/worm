package br.com.liviacare.worm.annotation.mapping;

import java.lang.annotation.*;

/**
 * Enables dirty tracking on an entity. When present, snapshots are captured
 * automatically on load and after writes, allowing {@link OrmOperations#update(Object)}
 * to detect changed columns and emit partial UPDATE SQL.
 *
 * Example:
 * <pre>
 * {@literal @}DbTable("users")
 * {@literal @}Track
 * public class User {
 *     {@literal @}DbId
 *     private UUID id;
 *     private String name;
 *     private String email;
 * }
 * </pre>
 *
 * <p>Without {@code @Track}, updates always emit full UPDATE statements with all updatable columns.
 * With {@code @Track}, only changed columns are included in the UPDATE.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Track {
}

