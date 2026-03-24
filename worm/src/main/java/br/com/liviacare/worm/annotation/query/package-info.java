/**
 * Query-related annotations for native SQL repositories.
 *
 * <p>This package provides annotations for declaring custom native SQL query methods
 * on repository interfaces, similar to Spring Data's {@code @Query} but for raw SQL:
 *
 * <ul>
 *   <li>{@link br.com.liviacare.worm.annotation.query.Query} - marks a repository method as executing native SQL</li>
 *   <li>{@link br.com.liviacare.worm.annotation.query.QueryParam} - binds method parameters to SQL placeholders</li>
 *   <li>{@link br.com.liviacare.worm.annotation.query.QueryRepository} - marks an interface as a query repository for auto-wiring</li>
 *   <li>{@link br.com.liviacare.worm.annotation.query.JlfQuery} - legacy query annotation (deprecated in favor of {@code Query})</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>
 * &#64;QueryRepository
 * public interface UserRepository {
 *     &#64;Query("select * from users where active = :active")
 *     List&lt;User&gt; findAllActive(&#64;QueryParam("active") Boolean active);
 * }
 * </pre>
 */
package br.com.liviacare.worm.annotation.query;

