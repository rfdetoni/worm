/**
 * Audit field annotations for automatic tracking and soft deletes.
 *
 * <p>This package contains annotations for automatic timestamp and user tracking,
 * as well as soft-delete support:
 *
 * <ul>
 *   <li>{@link br.com.liviacare.worm.annotation.audit.CreatedAt} - auto-populated with creation timestamp</li>
 *   <li>{@link br.com.liviacare.worm.annotation.audit.UpdatedAt} - auto-updated with modification timestamp</li>
 *   <li>{@link br.com.liviacare.worm.annotation.audit.CreatedBy} - tracks the user who created the entity</li>
 *   <li>{@link br.com.liviacare.worm.annotation.audit.UpdatedBy} - tracks the user who last modified the entity</li>
 *   <li>{@link br.com.liviacare.worm.annotation.audit.Active} - soft-delete flag (true = active, false = deleted)</li>
 *   <li>{@link br.com.liviacare.worm.annotation.audit.DeletedAt} - soft-delete timestamp (null = active, set = deleted)</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>
 * &#64;DbTable("users")
 * public class User implements iBaseEntity {
 *     &#64;DbId("id")
 *     private UUID id;
 *
 *     &#64;CreatedAt
 *     private Instant createdAt;
 *
 *     &#64;UpdatedAt
 *     private Instant updatedAt;
 *
 *     &#64;Active
 *     private Boolean active;
 * }
 * </pre>
 */
package br.com.liviacare.worm.annotation.audit;

