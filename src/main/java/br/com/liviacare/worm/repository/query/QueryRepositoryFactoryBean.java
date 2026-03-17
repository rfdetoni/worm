package br.com.liviacare.worm.repository.query;

import br.com.liviacare.worm.orm.OrmOperations;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Objects;

public class QueryRepositoryFactoryBean<T> implements FactoryBean<T>, InitializingBean {

    private final Class<T> repositoryInterface;

    private OrmOperations ormOperations;

    public QueryRepositoryFactoryBean(Class<T> repositoryInterface) {
        this.repositoryInterface = repositoryInterface;
    }

    @Autowired
    public void setOrmOperations(OrmOperations ormOperations) {
        this.ormOperations = ormOperations;
    }

    @Override
    public void afterPropertiesSet() {
        Objects.requireNonNull(ormOperations, "OrmOperations is required to build query repositories");
    }

    @Override
    public T getObject() {
        return QueryRepositoryFactory.create(repositoryInterface, ormOperations);
    }

    @Override
    public Class<?> getObjectType() {
        return repositoryInterface;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }
}


