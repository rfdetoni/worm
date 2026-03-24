/**
 * CRUD repository abstractions for data access patterns.
 *
 * <p>This package provides generic repository base classes and interfaces
 * for standard create, read, update, delete (CRUD) operations:
 *
 * <ul>
 *   <li>{@link br.com.liviacare.worm.repository.GenericRepository} - concrete repository base class</li>
 *   <li>{@link br.com.liviacare.worm.repository.LjfRepository} - CRUD interface contract</li>
 *   <li>{@link br.com.liviacare.worm.repository.RepositoryOperations} - minimal operations interface</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>
 * public class UserRepository extends GenericRepository&lt;User, UUID&gt; {
 *     public UserRepository(OrmOperations ormManager) {
 *         super(User.class, ormManager);
 *     }
 *
 *     public List&lt;User&gt; findActive() {
 *         return findAll(new FilterBuilder().eq("active", true));
 *     }
 * }
 * </pre>
 */
package br.com.liviacare.worm.repository.crud;

