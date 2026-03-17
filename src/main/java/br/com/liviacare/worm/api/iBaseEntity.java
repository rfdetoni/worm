package br.com.liviacare.worm.api;

import java.time.Instant;

/**
 * Base interface for entities that track audit information.
 * Implement this interface in your entity class to enable automatic
 * tracking of creation and deletion timestamps.
 *
 * Example:
 * <pre>
 * public class User implements iBaseEntity {
 *     private Instant createdAt;
 *     private Instant deletedAt;
 *
 *     @Override
 *     public void created() {
 *         this.createdAt = Instant.now();
 *     }
 *
 *     @Override
 *     public void deleted() {
 *         this.deletedAt = Instant.now();
 *     }
 *
 *     @Override
 *     public void updated() {
 *         // optional: update an updatedAt field
 *     }
 * }
 * </pre>
 */
public interface iBaseEntity {

    /**
     * Called automatically when the entity is being saved for the first time.
     * Implement this to set creation timestamps or audit fields.
     */
    void created();

    /**
     * Called automatically when the entity is being deleted.
     * Implement this to set deletion timestamps or soft-delete audit fields.
     */
    void deleted();

    /**
     * Called automatically when the entity is being updated.
     * Implement this to set update timestamps or other audit fields.
     */
    default void updated() {
        // Default no-op; override if needed
    }
}

