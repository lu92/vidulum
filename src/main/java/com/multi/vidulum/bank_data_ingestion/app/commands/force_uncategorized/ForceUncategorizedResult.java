package com.multi.vidulum.bank_data_ingestion.app.commands.force_uncategorized;

import com.multi.vidulum.bank_data_ingestion.domain.StagingSessionId;
import com.multi.vidulum.cashflow.domain.CashFlowId;

/**
 * Result of forcing PENDING_MAPPING transactions to use "Uncategorized" category.
 */
public record ForceUncategorizedResult(
        CashFlowId cashFlowId,
        StagingSessionId stagingSessionId,
        Status status,
        int transactionsUpdated,
        boolean categoryCreated,
        ValidationSummary validationSummary
) {

    public enum Status {
        SUCCESS,
        SESSION_NOT_FOUND,
        SESSION_EXPIRED,
        NO_PENDING_TRANSACTIONS
    }

    public record ValidationSummary(
            int totalTransactions,
            int validTransactions,
            int invalidTransactions,
            int duplicateTransactions,
            boolean readyForImport
    ) {}
}
