package com.multi.vidulum.bank_data_ingestion.app;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for Bank Data Ingestion module.
 */
@Configuration
public class BankDataIngestionConfig {

    @Value("${vidulum.ingestion.staging.ttl-hours:24}")
    private long stagingTtlHours;

    @Value("${vidulum.ingestion.rollback.window-hours:1}")
    private long rollbackWindowHours;

    @Value("${vidulum.ingestion.processing.batch-size:50}")
    private int batchSize;

    @Value("${vidulum.ingestion.processing.progress-update-interval:10}")
    private int progressUpdateInterval;

    public long getStagingTtlHours() {
        return stagingTtlHours;
    }

    public long getRollbackWindowHours() {
        return rollbackWindowHours;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public int getProgressUpdateInterval() {
        return progressUpdateInterval;
    }
}
