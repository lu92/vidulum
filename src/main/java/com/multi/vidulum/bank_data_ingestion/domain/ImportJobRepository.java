package com.multi.vidulum.bank_data_ingestion.domain;

import com.multi.vidulum.cashflow.domain.CashFlowId;

import java.util.List;
import java.util.Optional;

/**
 * Repository for ImportJob aggregates.
 */
public interface ImportJobRepository {

    /**
     * Save an import job.
     */
    ImportJob save(ImportJob job);

    /**
     * Find an import job by ID.
     */
    Optional<ImportJob> findById(ImportJobId id);

    /**
     * Find an import job by CashFlow ID and staging session ID.
     */
    Optional<ImportJob> findByCashFlowIdAndStagingSessionId(CashFlowId cashFlowId, StagingSessionId stagingSessionId);

    /**
     * Find all import jobs for a CashFlow.
     */
    List<ImportJob> findByCashFlowId(CashFlowId cashFlowId);

    /**
     * Find import jobs for a CashFlow with specific statuses.
     */
    List<ImportJob> findByCashFlowIdAndStatusIn(CashFlowId cashFlowId, List<ImportJobStatus> statuses);

    /**
     * Find the active (PENDING or PROCESSING) import job for a CashFlow.
     */
    Optional<ImportJob> findActiveJobByCashFlowId(CashFlowId cashFlowId);

    /**
     * Check if there's an active import job for a staging session.
     */
    boolean existsActiveByStagingSessionId(StagingSessionId stagingSessionId);
}
