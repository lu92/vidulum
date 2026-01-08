package com.multi.vidulum.bank_data_ingestion.domain;

import com.multi.vidulum.cashflow.domain.CashFlowId;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for StagedTransaction persistence operations.
 */
public interface StagedTransactionRepository {

    /**
     * Save a staged transaction.
     */
    StagedTransaction save(StagedTransaction stagedTransaction);

    /**
     * Save multiple staged transactions.
     */
    List<StagedTransaction> saveAll(List<StagedTransaction> stagedTransactions);

    /**
     * Find a staged transaction by its ID.
     */
    Optional<StagedTransaction> findById(StagedTransactionId stagedTransactionId);

    /**
     * Find all staged transactions for a given staging session.
     */
    List<StagedTransaction> findByStagingSessionId(StagingSessionId stagingSessionId);

    /**
     * Find all staged transactions for a given CashFlow.
     */
    List<StagedTransaction> findByCashFlowId(CashFlowId cashFlowId);

    /**
     * Check if a transaction with the given bank transaction ID already exists in staging.
     */
    Optional<StagedTransaction> findByCashFlowIdAndBankTransactionId(
            CashFlowId cashFlowId,
            String bankTransactionId
    );

    /**
     * Delete all staged transactions for a given staging session.
     */
    long deleteByStagingSessionId(StagingSessionId stagingSessionId);

    /**
     * Delete all staged transactions for a given CashFlow.
     */
    long deleteByCashFlowId(CashFlowId cashFlowId);

    /**
     * Count staged transactions for a given staging session.
     */
    long countByStagingSessionId(StagingSessionId stagingSessionId);

    /**
     * Count valid staged transactions for a given staging session.
     */
    long countValidByStagingSessionId(StagingSessionId stagingSessionId);

    /**
     * Count duplicate staged transactions for a given staging session.
     */
    long countDuplicatesByStagingSessionId(StagingSessionId stagingSessionId);

    /**
     * Count invalid staged transactions for a given staging session.
     */
    long countInvalidByStagingSessionId(StagingSessionId stagingSessionId);
}
