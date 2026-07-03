package com.github.rfdetoni.worm.example;

import com.github.rfdetoni.worm.config.query.QueryRepositoriesAutoConfiguration;
import com.github.rfdetoni.worm.orm.OrmOperations;
import com.github.rfdetoni.worm.query.FilterBuilder;
import com.github.rfdetoni.worm.query.Page;
import com.github.rfdetoni.worm.query.Pageable;
import com.github.rfdetoni.worm.query.Slice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = WormQueryRepoTest.Cfg.class, initializers = WormQueryRepoTest.ContextInitializer.class)
@TestPropertySource(locations = "classpath:application-test.properties")
public class WormQueryRepoTest {

    @Configuration
    @Import(QueryRepositoriesAutoConfiguration.class)
    static class Cfg {
        @Bean
        public OrmOperations ormOperations() {
            // Provide a minimal mock implementation for tests that only exercise repository wiring.
            return new OrmOperations() {
                @Override
                public <T> void save(T entity) {
                }

                @Override
                public <T> int[] saveAll(List<T> entities) {
                    return new int[0];
                }

                @Override
                public <T> int[] saveAllBatch(List<T> entities) {
                    return new int[0];
                }

                @Override
                public <T> void update(T entity) {
                }

                @Override
                public <T> void updatePartial(T entity, List<String> dirtyColumns) {
                }

                @Override
                public <T> int[] updateAll(List<T> entities) {
                    return new int[0];
                }

                @Override
                public <T> int[] updateAllBatch(List<T> entities) {
                    return new int[0];
                }

                @Override
                public <T> void delete(T entity) {
                }

                @Override
                public <T, I> void deleteById(Class<T> clazz, I id) {
                }

                @Override
                public <T> int[] deleteAll(List<T> entities) {
                    return new int[0];
                }

                @Override
                public <T> int[] deleteAllBatch(List<T> entities) {
                    return new int[0];
                }

                @Override
                public <T> void hardDelete(T entity) {
                }

                @Override
                public <T, I> void hardDeleteById(Class<T> clazz, I id) {
                }

                @Override
                public <T> int[] hardDeleteAll(List<T> entities) {
                    return new int[0];
                }

                @Override
                public <T> int[] hardDeleteAllBatch(List<T> entities) {
                    return new int[0];
                }

                @Override
                public <T> int[] upsertAll(List<T> entities) {
                    return new int[0];
                }

                @Override
                public <T> int[] upsertAllBatch(List<T> entities) {
                    return new int[0];
                }

                @Override
                public <T, I> java.util.Optional<T> findById(Class<T> clazz, I id) {
                    return java.util.Optional.empty();
                }

                @Override
                public <T, I> java.util.Optional<T> findById(Class<T> clazz, I id, String mainAlias) {
                    return java.util.Optional.empty();
                }

                @Override
                public <T, P> java.util.Optional<P> findById(Class<T> entityClass, Object id, Class<P> projectionClass) {
                    return java.util.Optional.empty();
                }

                @Override
                public <T> java.util.Optional<T> findOne(Class<T> clazz, FilterBuilder filter) {
                    return java.util.Optional.empty();
                }

                @Override
                public <T, P> java.util.Optional<P> findOne(Class<T> entityClass, FilterBuilder filter, Class<P> projectionClass) {
                    return java.util.Optional.empty();
                }

                @Override
                public <T> java.util.List<T> findAll(Class<T> clazz, FilterBuilder filter) {
                    return java.util.List.of();
                }

                @Override
                public <T> Slice<T> findAll(Class<T> clazz, FilterBuilder filter, Pageable pageable) {
                    return null;
                }

                @Override
                public <T, P> java.util.List<P> findAll(Class<T> entityClass, FilterBuilder filter, Class<P> projectionClass) {
                    return java.util.List.of();
                }

                @Override
                public <T> Page<T> findPage(Class<T> clazz, FilterBuilder filter, Pageable pageable) {
                    return null;
                }

                @Override
                public <T> java.util.List<T> findAllWithCte(Class<T> entityClass, FilterBuilder filterWithCte) {
                    return java.util.List.of();
                }

                @Override
                public <T, P> java.util.List<P> findAllWithCte(Class<T> entityClass, FilterBuilder filterWithCte, Class<P> projectionClass) {
                    return java.util.List.of();
                }

                @Override
                public <T> boolean exists(Class<T> clazz, FilterBuilder filter) {
                    return false;
                }

                @Override
                public <T, I> boolean existsById(Class<T> clazz, I id) {
                    return false;
                }

                @Override
                public <T> long count(Class<T> clazz) {
                    return 0;
                }

                @Override
                public <T> long count(Class<T> clazz, FilterBuilder filter) {
                    return 0;
                }

                @Override
                public <T, N extends Number> java.util.Optional<N> sum(Class<T> clazz, String column, Class<N> type, FilterBuilder filter) {
                    return java.util.Optional.empty();
                }

                @Override
                public <T, N extends Number> java.util.Optional<N> min(Class<T> clazz, String column, Class<N> type, FilterBuilder filter) {
                    return java.util.Optional.empty();
                }

                @Override
                public <T, N extends Number> java.util.Optional<N> max(Class<T> clazz, String column, Class<N> type, FilterBuilder filter) {
                    return java.util.Optional.empty();
                }

                @Override
                public <T> java.util.Optional<Double> avg(Class<T> clazz, String column, FilterBuilder filter) {
                    return java.util.Optional.empty();
                }

                @Override
                public <T, C> java.util.List<C> findColumn(Class<T> clazz, String columnName, Class<C> type, FilterBuilder filter) {
                    return java.util.List.of();
                }

                @Override
                public <T, C> java.util.Optional<C> findColumnOne(Class<T> clazz, String columnName, Class<C> type, FilterBuilder filter) {
                    return java.util.Optional.empty();
                }

                @Override
                public <T, R> java.util.List<R> queryList(Class<T> entityClass, String baseSql, Class<R> type, FilterBuilder filter) {
                    return java.util.List.of();
                }

                @Override
                public <T, R> java.util.Optional<R> queryOne(Class<T> entityClass, String baseSql, Class<R> type, FilterBuilder filter) {
                    return java.util.Optional.empty();
                }

                @Override
                public <T> java.util.Optional<String> jsonPathQueryFirst(Class<T> clazz, String column, String jsonPath, FilterBuilder filter) {
                    return java.util.Optional.empty();
                }

                @Override
                public <T> java.util.Optional<String> jsonPathQueryFirstWithVars(Class<T> clazz, String column, String jsonPath, Object varsJson, FilterBuilder filter) {
                    return java.util.Optional.empty();
                }

                @Override
                public <T> java.util.Optional<String> jsonPathQueryArray(Class<T> clazz, String column, String jsonPath, FilterBuilder filter) {
                    return java.util.Optional.empty();
                }

                @Override
                public <T> java.util.List<T> executeRaw(String sql, Class<T> resultClass, Object... params) {
                    return java.util.List.of((T) "x");
                }

                @Override
                public <T> java.util.List<T> executeRawPaged(String baseSql, Class<T> resultClass, int limit, long offset, Object... params) {
                    return java.util.List.of();
                }

                @Override
                public org.springframework.jdbc.core.simple.JdbcClient client() {
                    return null;
                }
            };
        }
    }

    public static class ContextInitializer implements org.springframework.context.ApplicationContextInitializer<org.springframework.context.ConfigurableApplicationContext> {
        @Override
        public void initialize(org.springframework.context.ConfigurableApplicationContext applicationContext) {
            org.springframework.beans.factory.support.DefaultListableBeanFactory registry = (org.springframework.beans.factory.support.DefaultListableBeanFactory) applicationContext.getBeanFactory();
            org.springframework.beans.factory.support.RootBeanDefinition def = new org.springframework.beans.factory.support.RootBeanDefinition();
            def.setTargetType(OrmOperations.class);
            def.setInstanceSupplier(() -> new OrmOperations() {
                @Override
                public <T> void save(T entity) {
                }

                @Override
                public <T> int[] saveAll(java.util.List<T> entities) {
                    return new int[0];
                }

                @Override
                public <T> int[] saveAllBatch(java.util.List<T> entities) {
                    return new int[0];
                }

                @Override
                public <T> void update(T entity) {
                }

                @Override
                public <T> void updatePartial(T entity, java.util.List<String> dirtyColumns) {
                }

                @Override
                public <T> int[] updateAll(java.util.List<T> entities) {
                    return new int[0];
                }

                @Override
                public <T> int[] updateAllBatch(java.util.List<T> entities) {
                    return new int[0];
                }

                @Override
                public <T> void delete(T entity) {
                }

                @Override
                public <T, I> void deleteById(Class<T> clazz, I id) {
                }

                @Override
                public <T> int[] deleteAll(java.util.List<T> entities) {
                    return new int[0];
                }

                @Override
                public <T> int[] deleteAllBatch(java.util.List<T> entities) {
                    return new int[0];
                }

                @Override
                public <T> void hardDelete(T entity) {
                }

                @Override
                public <T, I> void hardDeleteById(Class<T> clazz, I id) {
                }

                @Override
                public <T> int[] hardDeleteAll(java.util.List<T> entities) {
                    return new int[0];
                }

                @Override
                public <T> int[] hardDeleteAllBatch(java.util.List<T> entities) {
                    return new int[0];
                }

                @Override
                public <T> int[] upsertAll(java.util.List<T> entities) {
                    return new int[0];
                }

                @Override
                public <T> int[] upsertAllBatch(java.util.List<T> entities) {
                    return new int[0];
                }

                @Override
                public <T, I> java.util.Optional<T> findById(Class<T> clazz, I id) {
                    return java.util.Optional.empty();
                }

                @Override
                public <T, I> java.util.Optional<T> findById(Class<T> clazz, I id, String mainAlias) {
                    return java.util.Optional.empty();
                }

                @Override
                public <T, P> java.util.Optional<P> findById(Class<T> entityClass, Object id, Class<P> projectionClass) {
                    return java.util.Optional.empty();
                }

                @Override
                public <T> java.util.Optional<T> findOne(Class<T> clazz, FilterBuilder filter) {
                    return java.util.Optional.empty();
                }

                @Override
                public <T, P> java.util.Optional<P> findOne(Class<T> entityClass, FilterBuilder filter, Class<P> projectionClass) {
                    return java.util.Optional.empty();
                }

                @Override
                public <T> java.util.List<T> findAll(Class<T> clazz, FilterBuilder filter) {
                    return java.util.List.of();
                }

                @Override
                public <T> Slice<T> findAll(Class<T> clazz, FilterBuilder filter, Pageable pageable) {
                    return null;
                }

                @Override
                public <T, P> java.util.List<P> findAll(Class<T> entityClass, FilterBuilder filter, Class<P> projectionClass) {
                    return java.util.List.of();
                }

                @Override
                public <T> Page<T> findPage(Class<T> clazz, FilterBuilder filter, Pageable pageable) {
                    return null;
                }

                @Override
                public <T> java.util.List<T> findAllWithCte(Class<T> entityClass, FilterBuilder filterWithCte) {
                    return java.util.List.of();
                }

                @Override
                public <T, P> java.util.List<P> findAllWithCte(Class<T> entityClass, FilterBuilder filterWithCte, Class<P> projectionClass) {
                    return java.util.List.of();
                }

                @Override
                public <T> boolean exists(Class<T> clazz, FilterBuilder filter) {
                    return false;
                }

                @Override
                public <T, I> boolean existsById(Class<T> clazz, I id) {
                    return false;
                }

                @Override
                public <T> long count(Class<T> clazz) {
                    return 0;
                }

                @Override
                public <T> long count(Class<T> clazz, FilterBuilder filter) {
                    return 0;
                }

                @Override
                public <T, N extends Number> java.util.Optional<N> sum(Class<T> clazz, String column, Class<N> type, FilterBuilder filter) {
                    return java.util.Optional.empty();
                }

                @Override
                public <T, N extends Number> java.util.Optional<N> min(Class<T> clazz, String column, Class<N> type, FilterBuilder filter) {
                    return java.util.Optional.empty();
                }

                @Override
                public <T, N extends Number> java.util.Optional<N> max(Class<T> clazz, String column, Class<N> type, FilterBuilder filter) {
                    return java.util.Optional.empty();
                }

                @Override
                public <T> java.util.Optional<Double> avg(Class<T> clazz, String column, FilterBuilder filter) {
                    return java.util.Optional.empty();
                }

                @Override
                public <T, C> java.util.List<C> findColumn(Class<T> clazz, String columnName, Class<C> type, FilterBuilder filter) {
                    return java.util.List.of();
                }

                @Override
                public <T, C> java.util.Optional<C> findColumnOne(Class<T> clazz, String columnName, Class<C> type, FilterBuilder filter) {
                    return java.util.Optional.empty();
                }

                @Override
                public <T, R> java.util.List<R> queryList(Class<T> entityClass, String baseSql, Class<R> type, FilterBuilder filter) {
                    return java.util.List.of();
                }

                @Override
                public <T, R> java.util.Optional<R> queryOne(Class<T> entityClass, String baseSql, Class<R> type, FilterBuilder filter) {
                    return java.util.Optional.empty();
                }

                @Override
                public <T> java.util.Optional<String> jsonPathQueryFirst(Class<T> clazz, String column, String jsonPath, FilterBuilder filter) {
                    return java.util.Optional.empty();
                }

                @Override
                public <T> java.util.Optional<String> jsonPathQueryFirstWithVars(Class<T> clazz, String column, String jsonPath, Object varsJson, FilterBuilder filter) {
                    return java.util.Optional.empty();
                }

                @Override
                public <T> java.util.Optional<String> jsonPathQueryArray(Class<T> clazz, String column, String jsonPath, FilterBuilder filter) {
                    return java.util.Optional.empty();
                }

                @Override
                public <T> java.util.List<T> executeRaw(String sql, Class<T> resultClass, Object... params) {
                    return java.util.List.of((T) "x");
                }

                @Override
                public <T> java.util.List<T> executeRawPaged(String baseSql, Class<T> resultClass, int limit, long offset, Object... params) {
                    return java.util.List.of();
                }

                @Override
                public org.springframework.jdbc.core.simple.JdbcClient client() {
                    return null;
                }
            });
            registry.registerBeanDefinition("ormOperations", def);
            // Now invoke the library registrar directly so it can scan and register query repositories
            try {
                QueryRepositoriesAutoConfiguration.QueryRepositoriesRegistrar registrar =
                        new QueryRepositoriesAutoConfiguration.QueryRepositoriesRegistrar();
                registrar.setEnvironment(applicationContext.getEnvironment());
                registrar.registerBeanDefinitions(null, registry);
            } catch (Exception ex) {
                throw new RuntimeException("Failed to run QueryRepositoriesRegistrar", ex);
            }
        }

        // Let QueryRepositoriesAutoConfiguration scan and register SampleQueryRepository from main sources
    }

    @Bean
    public static org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor invokeQueryRegistrar(org.springframework.core.env.Environment environment) {
        return new org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor() {
            @Override
            public void postProcessBeanDefinitionRegistry(org.springframework.beans.factory.support.BeanDefinitionRegistry registry) throws org.springframework.beans.BeansException {
                QueryRepositoriesAutoConfiguration.QueryRepositoriesRegistrar registrar = new QueryRepositoriesAutoConfiguration.QueryRepositoriesRegistrar();
                registrar.setEnvironment(environment);
                registrar.registerBeanDefinitions(null, registry);
            }

            @Override
            public void postProcessBeanFactory(org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory) throws org.springframework.beans.BeansException {
                // no-op
            }
        };
    }

    @Autowired
    SampleQueryRepository repo;

    @Test
    void repositoryBeanIsCreatedAndInjectable() {
        assertNotNull(repo, "repository bean should be injected");
        List<String> results = repo.findValues();
        assertTrue(results.contains("x"), "mock OrmOperations returns 'x' via executeRaw stub");
    }
}
