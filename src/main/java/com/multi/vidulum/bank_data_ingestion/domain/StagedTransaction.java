package com.multi.vidulum.bank_data_ingestion.domain;

import com.multi.vidulum.cashflow.domain.CashFlowId;

import java.time.ZonedDateTime;

/**
 * Domain object representing a transaction staged for import.
 * Staged transactions are temporary and will be deleted after TTL expires or after import.
 *
 * @param stagedTransactionId unique identifier for this staged transaction
 * @param cashFlowId          the CashFlow this transaction will be imported to
 * @param stagingSessionId    groups transactions from the same staging operation
 * @param originalData        original data from the bank
 * @param mappedData          data after applying category mappings
 * @param validation          validation result
 * @param createdAt           when this staged transaction was created
 * @param expiresAt           when this staged transaction will expire (TTL)
 */
public record StagedTransaction(
        StagedTransactionId stagedTransactionId,
        CashFlowId cashFlowId,
        StagingSessionId stagingSessionId,
        OriginalTransactionData originalData,
        MappedTransactionData mappedData,
        TransactionValidation validation,
        ZonedDateTime createdAt,
        ZonedDateTime expiresAt
) {

    public static StagedTransaction create(
            CashFlowId cashFlowId,
            StagingSessionId stagingSessionId,
            OriginalTransactionData originalData,
            MappedTransactionData mappedData,
            TransactionValidation validation,
            ZonedDateTime now,
            long ttlHours
    ) {
        return new StagedTransaction(
                StagedTransactionId.generate(),
                cashFlowId,
                stagingSessionId,
                originalData,
                mappedData,
                validation,
                now,
                now.plusHours(ttlHours)
        );
    }

    public boolean isValid() {
        return validation.status() == ValidationStatus.VALID;
    }

    public boolean isDuplicate() {
        return validation.status() == ValidationStatus.DUPLICATE;
    }

    public boolean isInvalid() {
        return validation.status() == ValidationStatus.INVALID;
    }
}
