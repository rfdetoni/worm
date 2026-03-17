package br.com.liviacare.worm.config.query;

import br.com.liviacare.worm.annotation.query.QueryRepository;
import br.com.liviacare.worm.orm.OrmOperations;
import br.com.liviacare.worm.repository.query.QueryRepositoryFactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;

import java.util.HashSet;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(OrmOperations.class)
@EnableConfigurationProperties(QueryRepositoryProperties.class)
@Import(QueryRepositoriesAutoConfiguration.QueryRepositoriesRegistrar.class)
public class QueryRepositoriesAutoConfiguration {

    static final class QueryRepositoriesRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware {

        private Environment environment;

        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
            QueryRepositoryProperties properties = bindProperties();
            String[] basePackages = properties.getBasePackages();
            if (basePackages == null || basePackages.length == 0) {
                return;
            }
            ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false, environment);
            scanner.addIncludeFilter(new AnnotationTypeFilter(QueryRepository.class));
            Set<String> registered = new HashSet<>();
            for (String basePackage : basePackages) {
                if (!StringUtils.hasText(basePackage)) continue;
                for (var candidate : scanner.findCandidateComponents(basePackage)) {
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
            return binder.bind("worm.query.repository", QueryRepositoryProperties.class)
                    .orElse(new QueryRepositoryProperties());
        }
    }
}



