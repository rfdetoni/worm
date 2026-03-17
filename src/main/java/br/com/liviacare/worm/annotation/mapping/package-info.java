/**
 * Mapping and metadata annotations for entity definitions.
 *
 * <p>This package contains annotations used to map Java classes to database tables
 * and configure column mappings, joins, and version tracking:
 *
 * <ul>
 *   <li>{@link br.com.liviacare.worm.annotation.mapping.DbTable} - maps a class to a database table</li>
 *   <li>{@link br.com.liviacare.worm.annotation.mapping.DbId} - marks a field as the primary key</li>
 *   <li>{@link br.com.liviacare.worm.annotation.mapping.DbColumn} - maps a field to a database column</li>
 *   <li>{@link br.com.liviacare.worm.annotation.mapping.DbJoin} - defines a relationship/join to another entity</li>
 *   <li>{@link br.com.liviacare.worm.annotation.mapping.DbVersion} - marks a field for optimistic locking</li>
 *   <li>{@link br.com.liviacare.worm.annotation.mapping.OrderBy} - specifies default sort order</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>
 * &#64;DbTable("users")
 * public class User {
 *     &#64;DbId("id")
 *     private UUID id;
 *
 *     &#64;DbColumn("name")
 *     private String name;
 *
 *     &#64;DbJoin(localColumn = "dept_id")
 *     private Department department;
 * }
 * </pre>
 */
package br.com.liviacare.worm.annotation.mapping;

