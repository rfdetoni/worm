package br.com.liviacare.worm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the WORM ORM framework.
 * Properties can be configured in application.yaml or application.properties.
 *
 * Example:
 * <pre>
 * worm:
 *   batch-size: 1000
 *   enable-schema-validation: true
 * </pre>
 */
@ConfigurationProperties(prefix = "worm")
public class WormProperties {

    /** Batch size for batch insert/update operations. Default: 500 */
    private int batchSize = 500;

    /** Enable schema validation at startup. Default: false */
    private boolean enableSchemaValidation = false;

    public WormProperties() {
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public boolean isEnableSchemaValidation() {
        return enableSchemaValidation;
    }

    public void setEnableSchemaValidation(boolean enableSchemaValidation) {
        this.enableSchemaValidation = enableSchemaValidation;
    }

    @Override
    public String toString() {
        return "WormProperties{" +
                "batchSize=" + batchSize +
                ", enableSchemaValidation=" + enableSchemaValidation +
                '}';
    }
}

