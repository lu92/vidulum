package com.multi.vidulum.bank_data_ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Settings for the whole module, which is why it sits at the module root rather than in a section.
 *
 * <p>Its four values belong to <b>different</b> sections - the staging TTL to staging, the rollback
 * window and the batch size to importing - so it is nobody's property and splitting a four-field
 * holder into two {@code @Configuration} classes would be ceremony. Keeping it here also keeps the
 * sections free of a cycle: when it lived in {@code app} (the unextracted staging code),
 * {@code importing} had to reach into {@code app} to read a batch size, and
 * {@code CanonicalRowIsSelfContainedTest} reported that as a cycle - correctly.
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
