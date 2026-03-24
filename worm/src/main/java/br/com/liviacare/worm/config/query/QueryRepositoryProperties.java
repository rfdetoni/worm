package br.com.liviacare.worm.config.query;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.Arrays;
import java.util.Objects;

@ConfigurationProperties(prefix = "worm.query.repository")
public class QueryRepositoryProperties {

        /** Packages to scan for interfaces annotated with {@link br.com.liviacare.worm.annotation.query.QueryRepository}. */
    private String[] basePackages = new String[]{"br.com.liviacare"};

    public String[] getBasePackages() {
        return basePackages;
    }

    public void setBasePackages(String[] basePackages) {
        this.basePackages = Objects.requireNonNull(basePackages);
    }

    public boolean isEmpty() {
        return basePackages.length == 0;
    }

    @Override
    public String toString() {
        return "QueryRepositoryProperties" + Arrays.toString(basePackages);
    }
}


