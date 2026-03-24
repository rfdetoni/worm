/**
 * Native SQL query repository abstractions.
 *
 * <p>This package provides runtime proxy generation and factory support for
 * repositories annotated with {@code @QueryRepository} that execute native SQL:
 *
 * <ul>
 *   <li>{@link br.com.liviacare.worm.repository.query.QueryRepositoryFactory} - factory to create repository proxies</li>
 *   <li>{@link br.com.liviacare.worm.repository.query.QueryRepositoryFactoryBean} - Spring FactoryBean for wiring proxies</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>
 * &#64;QueryRepository
 * public interface UserRepository {
 *     &#64;Query("select * from users where active = :active")
 *     List&lt;User&gt; findAllActive(&#64;QueryParam("active") Boolean active);
 * }
 *
 * // Auto-wired by Spring or created manually:
 * UserRepository repo = QueryRepositoryFactory.create(UserRepository.class, ormOperations);
 * </pre>
 */
package br.com.liviacare.worm.repository.query;

