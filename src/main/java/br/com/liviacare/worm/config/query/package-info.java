/**
 * Native SQL query repository auto-configuration.
 *
 * <p>This package provides Spring Boot auto-configuration for native SQL query repositories:
 *
 * <ul>
 *   <li>{@link br.com.liviacare.worm.config.query.QueryRepositoriesAutoConfiguration} - auto-discovers and registers {@code @QueryRepository} beans</li>
 *   <li>{@link br.com.liviacare.worm.config.query.QueryRepositoryProperties} - configuration properties for query repository scanning</li>
 * </ul>
 *
 * <p>Configuration properties in {@code application.yaml}:
 * <pre>
 * worm:
 *   query:
 *     repository:
 *       base-packages:
 *         - com.example.repositories
 * </pre>
 */
package br.com.liviacare.worm.config.query;

