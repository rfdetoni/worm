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
 *   insert-strategy: UPSERT
 *   bulk-copy-threshold: 20
 *   bulk-unnest-threshold: 10
 *   async-sql-log-enabled: true
 *   async-sql-log-queue-size: 8192
 * </pre>
 */
@ConfigurationProperties(prefix = "worm")
public class WormProperties {

    /**
     * Estratégia para save() de entidades com ID pré-gerado (ex: UUIDv7).
     * UPSERT     — emite ON CONFLICT DO UPDATE (1 round-trip, recomendado)
     * TRY_UPDATE — UPDATE first, INSERT on 0 rows (2 round-trips para entidade nova)
     * INSERT_ONLY — sempre INSERT, lança exceção em duplicata
     */
    public enum InsertStrategy { UPSERT, TRY_UPDATE, INSERT_ONLY }

    /** Batch size for batch insert/update operations. Default: 500 */
    private int batchSize = 500;

    /** Enable schema validation at startup. Default: false */
    private boolean enableSchemaValidation = false;

    /**
     * When true, save() attempts UPDATE first (for entities with ID) and falls back to INSERT
     * if no rows are affected. Only used when insertStrategy=TRY_UPDATE.
     */
    private boolean saveTryUpdateFirst = true;

    /**
     * When true, single-row writes are executed inside a TransactionTemplate when available,
     * reducing auto-commit/fsync overhead on databases like PostgreSQL.
     */
    private boolean transactionEnabled = true;

    /**
     * Estratégia de insert para save() com ID pré-gerado. Default: UPSERT (1 round-trip).
     */
    private InsertStrategy insertStrategy = InsertStrategy.UPSERT;

    /** Threshold mínimo para usar COPY FROM STDIN em bulk insert. Default: 20 */
    private int bulkCopyThreshold = 20;

    /** Threshold mínimo para usar unnest em bulk update/delete. Default: 10 */
    private int bulkUnnestThreshold = 10;

    /** Enable async SQL debug logging dispatch. Default: true */
    private boolean asyncSqlLogEnabled = true;

    /** Queue size for async SQL logging dispatcher. Default: 8192 */
    private int asyncSqlLogQueueSize = 8192;

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

    public boolean isTransactionEnabled() {
        return transactionEnabled;
    }

    public void setTransactionEnabled(boolean transactionEnabled) {
        this.transactionEnabled = transactionEnabled;
    }

    public InsertStrategy getInsertStrategy() {
        return insertStrategy;
    }

    public void setInsertStrategy(InsertStrategy insertStrategy) {
        this.insertStrategy = insertStrategy;
    }

    public int getBulkCopyThreshold() {
        return bulkCopyThreshold;
    }

    public void setBulkCopyThreshold(int bulkCopyThreshold) {
        this.bulkCopyThreshold = bulkCopyThreshold;
    }

    public int getBulkUnnestThreshold() {
        return bulkUnnestThreshold;
    }

    public void setBulkUnnestThreshold(int bulkUnnestThreshold) {
        this.bulkUnnestThreshold = bulkUnnestThreshold;
    }

    public boolean isAsyncSqlLogEnabled() {
        return asyncSqlLogEnabled;
    }

    public void setAsyncSqlLogEnabled(boolean asyncSqlLogEnabled) {
        this.asyncSqlLogEnabled = asyncSqlLogEnabled;
    }

    public int getAsyncSqlLogQueueSize() {
        return asyncSqlLogQueueSize;
    }

    public void setAsyncSqlLogQueueSize(int asyncSqlLogQueueSize) {
        this.asyncSqlLogQueueSize = asyncSqlLogQueueSize;
    }

    @Override
    public String toString() {
        return "WormProperties{" +
                "batchSize=" + batchSize +
                ", enableSchemaValidation=" + enableSchemaValidation +
                ", saveTryUpdateFirst=" + saveTryUpdateFirst +
                ", transactionEnabled=" + transactionEnabled +
                ", insertStrategy=" + insertStrategy +
                ", bulkCopyThreshold=" + bulkCopyThreshold +
                ", bulkUnnestThreshold=" + bulkUnnestThreshold +
                ", asyncSqlLogEnabled=" + asyncSqlLogEnabled +
                ", asyncSqlLogQueueSize=" + asyncSqlLogQueueSize +
                '}';
    }
}

