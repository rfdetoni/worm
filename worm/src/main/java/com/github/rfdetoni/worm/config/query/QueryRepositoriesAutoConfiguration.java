package com.github.rfdetoni.worm.config.query;

import com.github.rfdetoni.worm.annotation.query.QueryRepository;
import com.github.rfdetoni.worm.orm.OrmOperations;
import com.github.rfdetoni.worm.repository.query.QueryRepositoryFactoryBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(OrmOperations.class)
@EnableConfigurationProperties(QueryRepositoryProperties.class)
@Import(QueryRepositoriesAutoConfiguration.QueryRepositoriesRegistrar.class)
public class QueryRepositoriesAutoConfiguration {

    public static final class QueryRepositoriesRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware {

        private Environment environment;
        private static final Logger log = LoggerFactory.getLogger(QueryRepositoriesRegistrar.class);

        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
            QueryRepositoryProperties properties = bindProperties();
            String[] basePackages = properties.getBasePackages();
            log.debug("Resolved QueryRepository basePackages: {}", (Object) basePackages);
            if (basePackages == null || basePackages.length == 0) {
                return;
            }
            ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false, environment);
            // Ensure the scanner has a resource loader so it can discover classes when invoked
            // outside of a full Spring ApplicationContext (tests call the registrar directly).
            try {
                org.springframework.core.io.support.PathMatchingResourcePatternResolver resolver =
                        new org.springframework.core.io.support.PathMatchingResourcePatternResolver(ClassUtils.getDefaultClassLoader());
                scanner.setResourceLoader(resolver);
            } catch (Exception ex) {
                log.debug("Could not set custom ResourceLoader on scanner, proceeding with defaults", ex);
            }
            scanner.addIncludeFilter(new AnnotationTypeFilter(QueryRepository.class));
            Set<String> registered = new HashSet<>();
            for (String basePackage : basePackages) {
                if (!StringUtils.hasText(basePackage)) continue;
                var candidates = scanner.findCandidateComponents(basePackage);
                if (log.isDebugEnabled()) {
                    for (var c : candidates) {
                        log.debug("Found candidate component: {}", c.getBeanClassName());
                    }
                }
                if (candidates.isEmpty()) {
                    // Try a fallback scan that inspects classpath entries (files and jars).
                    try {
                        fallbackScan(registry, basePackage, registered);
                    } catch (Exception ex) {
                        log.debug("Fallback scan failed for package {}", basePackage, ex);
                    }
                }
                for (var candidate : candidates) {
                    String className = candidate.getBeanClassName();
                    if (className == null) continue;
                    try {
                        Class<?> repositoryInterface = ClassUtils.forName(className, null);
                        registerRepositoryBean(registry, repositoryInterface, registered);
                    } catch (ClassNotFoundException ex) {
                        throw new IllegalStateException("Failed to load query repository " + className, ex);
                    }
                }
            }
        }

        private void registerRepositoryBean(BeanDefinitionRegistry registry, Class<?> repositoryInterface, Set<String> registered) {
            if (!repositoryInterface.isInterface()) return;
            String beanName = StringUtils.uncapitalize(repositoryInterface.getSimpleName());
            if (registered.contains(beanName)) return;
            log.info("Registering QueryRepository bean for interface {} with bean name {}", repositoryInterface.getName(), beanName);
            BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(QueryRepositoryFactoryBean.class);
            builder.addConstructorArgValue(repositoryInterface);
            builder.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
            builder.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_TYPE);
            AbstractBeanDefinition beanDefinition = builder.getBeanDefinition();
            registry.registerBeanDefinition(beanName, beanDefinition);
            registered.add(beanName);
        }

        private QueryRepositoryProperties bindProperties() {
            Binder binder = Binder.get(environment);
            var bound = binder.bind("worm.query.repository", QueryRepositoryProperties.class);
            QueryRepositoryProperties boundProps = bound.orElse(null);
            if (boundProps != null) {
                log.debug("Bound QueryRepositoryProperties via Binder: {}", boundProps);
                return boundProps;
            }
            // Fallback: try common property names and formats if Binder didn't pick them up
            QueryRepositoryProperties props = new QueryRepositoryProperties();
            boolean found = false;
            // try array/list with kebab-case
            String[] basePackages = environment.getProperty("worm.query.repository.base-packages", String[].class);
            if (basePackages != null && basePackages.length > 0) {
                props.setBasePackages(basePackages);
                found = true;
                return props;
            }
            // try array/list with camelCase
            basePackages = environment.getProperty("worm.query.repository.basePackages", String[].class);
            if (basePackages != null && basePackages.length > 0) {
                props.setBasePackages(basePackages);
                found = true;
                return props;
            }
            // try comma-separated single property (kebab-case)
            String csv = environment.getProperty("worm.query.repository.base-packages");
            if (csv != null) {
                props.setBasePackages(StringUtils.commaDelimitedListToStringArray(csv));
                found = true;
                return props;
            }
            // try comma-separated single property (camelCase)
            csv = environment.getProperty("worm.query.repository.basePackages");
            if (csv != null) {
                props.setBasePackages(StringUtils.commaDelimitedListToStringArray(csv));
                found = true;
                return props;
            }
            if (!found) {
                // As a last-resort convenience for applications where users forget to declare
                // the property, scan the top-level 'br' package (common in this codebase)
                // so interfaces like br.com.worm.demo.BookQueryRepository are discovered.
                // This avoids surprising 'No qualifying bean' errors in demos/tests.
                props.setBasePackages(new String[]{"br"});
                log.warn("No explicit 'worm.query.repository.base-packages' found; falling back to scanning 'br' package."
                        + " For deterministic behavior set worm.query.repository.base-packages to your repository package(s).");
            } else {
                log.debug("QueryRepositoryProperties resolved from Environment");
            }
            return props;
        }

        // Simple fallback scanner that inspects classpath entries (file system and jars).
        private void fallbackScan(BeanDefinitionRegistry registry, String basePackage, Set<String> registered) throws Exception {
            ClassLoader cl = ClassUtils.getDefaultClassLoader();
            String path = basePackage.replace('.', '/');
            java.util.Enumeration<java.net.URL> resources = cl.getResources(path);
            while (resources.hasMoreElements()) {
                java.net.URL url = resources.nextElement();
                String protocol = url.getProtocol();
                if ("file".equals(protocol)) {
                    java.nio.file.Path dir = java.nio.file.Paths.get(url.toURI());
                    try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(dir)) {
                        stream.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                            String filePath = dir.relativize(p).toString();
                            String className = (basePackage + "." + filePath.replace(java.io.File.separatorChar, '.')).replaceAll("\\.class$", "");
                            try {
                                Class<?> cls = ClassUtils.forName(className, cl);
                                if (cls.isInterface() && cls.isAnnotationPresent(QueryRepository.class)) {
                                    registerRepositoryBean(registry, cls, registered);
                                }
                            } catch (Throwable ignore) {
                                // ignore classes we cannot load
                            }
                        });
                    }
                } else if ("jar".equals(protocol) || url.toString().startsWith("jar:")) {
                    String urlFile = url.getFile();
                    String jarPath = urlFile;
                    int idx = jarPath.indexOf('!');
                    if (idx != -1) jarPath = jarPath.substring(0, idx);
                    if (jarPath.startsWith("file:")) jarPath = jarPath.substring("file:".length());
                    jarPath = java.net.URLDecoder.decode(jarPath, java.nio.charset.StandardCharsets.UTF_8);
                    try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarPath)) {
                        java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            java.util.jar.JarEntry entry = entries.nextElement();
                            String name = entry.getName();
                            if (!name.startsWith(path) || !name.endsWith(".class")) continue;
                            String className = name.replace('/', '.').replaceAll("\\.class$", "");
                            try {
                                Class<?> cls = ClassUtils.forName(className, cl);
                                if (cls.isInterface() && cls.isAnnotationPresent(QueryRepository.class)) {
                                    registerRepositoryBean(registry, cls, registered);
                                }
                            } catch (Throwable ignore) {
                                // ignore
                            }
                        }
                    }
                }
            }
        }
    }
}



