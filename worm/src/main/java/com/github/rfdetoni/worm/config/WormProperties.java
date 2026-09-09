package com.github.rfdetoni.worm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the WORM ORM framework.
 *
 * <pre>
 * worm:
 *   batch-size: 1000
 *   insert-strategy: UPSERT
 *   bulk-copy-threshold: 20
 *   bulk-unnest-threshold: 10
 *   query-plan-cache-max-entries: 4096
 *   async-sql-log-enabled: true
 *   async-sql-log-queue-size: 8192
 *   warmup-enabled: true
 *   metrics-enabled: true
 * </pre>
 */
@ConfigurationProperties(prefix = "worm")
public class WormProperties {

    public enum InsertStrategy { UPSERT, TRY_UPDATE, INSERT_ONLY }

    /** Batch size for batch insert/update operations. Default: 500 */
    private int batchSize = 500;

    /** Enable schema validation at startup. Default: false */
    private boolean enableSchemaValidation = false;

    /** Used only when insertStrategy=TRY_UPDATE. */
    private boolean saveTryUpdateFirst = true;

    /** Wrap single-row writes in TransactionTemplate when available. */
    private boolean transactionEnabled = true;

    /** Strategy for save() with a pre-generated ID. */
    private InsertStrategy insertStrategy = InsertStrategy.UPSERT;

    /** Minimum threshold for COPY FROM STDIN bulk inserts. */
    private int bulkCopyThreshold = 20;

    /** Minimum threshold for unnest-based bulk update/delete. */
    private int bulkUnnestThreshold = 10;

    /**
     * Upper bound for rendered SQL query plans kept in memory.
     *
     * <p>Pagination can produce distinct SQL shapes for different offsets. Bounding
     * the cache avoids unbounded memory retention on workloads that traverse many pages.</p>
     */
    private int queryPlanCacheMaxEntries = 4096;

    /** Enable async SQL debug logging dispatch. */
    private boolean asyncSqlLogEnabled = true;

    /** Queue size for async SQL logging dispatcher. */
    private int asyncSqlLogQueueSize = 8192;

    /** Enable two-phase parallel mapping for bulk queries. */
    private boolean parallelMappingEnabled = false;

    /** Minimum rows required to trigger parallel mapping. */
    private int parallelMappingThreshold = 1000;

    /** Enable startup warmup of prepared statements. */
    private boolean warmupEnabled = false;

    /** Enable latency metrics recording. */
    private boolean metricsEnabled = false;

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

    public int getQueryPlanCacheMaxEntries() {
        return queryPlanCacheMaxEntries;
    }

    public void setQueryPlanCacheMaxEntries(int queryPlanCacheMaxEntries) {
        this.queryPlanCacheMaxEntries = queryPlanCacheMaxEntries;
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

    public boolean isParallelMappingEnabled() {
        return parallelMappingEnabled;
    }

    public void setParallelMappingEnabled(boolean parallelMappingEnabled) {
        this.parallelMappingEnabled = parallelMappingEnabled;
    }

    public int getParallelMappingThreshold() {
        return parallelMappingThreshold;
    }

    public void setParallelMappingThreshold(int parallelMappingThreshold) {
        this.parallelMappingThreshold = parallelMappingThreshold;
    }

    public boolean isWarmupEnabled() {
        return warmupEnabled;
    }

    public void setWarmupEnabled(boolean warmupEnabled) {
        this.warmupEnabled = warmupEnabled;
    }

    public boolean isMetricsEnabled() {
        return metricsEnabled;
    }

    public void setMetricsEnabled(boolean metricsEnabled) {
        this.metricsEnabled = metricsEnabled;
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
                ", queryPlanCacheMaxEntries=" + queryPlanCacheMaxEntries +
                ", asyncSqlLogEnabled=" + asyncSqlLogEnabled +
                ", asyncSqlLogQueueSize=" + asyncSqlLogQueueSize +
                ", parallelMappingEnabled=" + parallelMappingEnabled +
                ", parallelMappingThreshold=" + parallelMappingThreshold +
                ", warmupEnabled=" + warmupEnabled +
                ", metricsEnabled=" + metricsEnabled +
                '}';
    }
}
