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

    /**
     * When true, save() attempts UPDATE first (for entities with ID) and falls back to INSERT
     * if no rows are affected. This avoids a read-before-write existsById() round-trip.
     */
    private boolean saveTryUpdateFirst = true;

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

    public boolean isSaveTryUpdateFirst() {
        return saveTryUpdateFirst;
    }

    public void setSaveTryUpdateFirst(boolean saveTryUpdateFirst) {
        this.saveTryUpdateFirst = saveTryUpdateFirst;
    }

    @Override
    public String toString() {
        return "WormProperties{" +
                "batchSize=" + batchSize +
                ", enableSchemaValidation=" + enableSchemaValidation +
                ", saveTryUpdateFirst=" + saveTryUpdateFirst +
                '}';
    }
}

