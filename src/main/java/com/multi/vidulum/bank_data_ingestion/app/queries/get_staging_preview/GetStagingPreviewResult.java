package com.multi.vidulum.bank_data_ingestion.app.queries.get_staging_preview;

import com.multi.vidulum.bank_data_ingestion.domain.StagingSessionId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.common.Money;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Result of getting a staging preview.
 */
public record GetStagingPreviewResult(
        StagingSessionId stagingSessionId,
        CashFlowId cashFlowId,
        StagingStatus status,
        ZonedDateTime expiresAt,
        StagingSummary summary,
        List<StagedTransactionPreview> transactions,
        List<CategoryBreakdown> categoryBreakdown,
        List<CategoryToCreate> categoriesToCreate,
        List<MonthlyBreakdown> monthlyBreakdown
) {

    public enum StagingStatus {
        READY_FOR_IMPORT,
        HAS_VALIDATION_ERRORS,
        EXPIRED,
        NOT_FOUND
    }

    public record StagingSummary(
            int totalTransactions,
            int validTransactions,
            int invalidTransactions,
            int duplicateTransactions
    ) {
    }

    public record StagedTransactionPreview(
            String stagedTransactionId,
            String bankTransactionId,
            String name,
            String description,
            String bankCategory,
            String targetCategory,
            String parentCategory,
            Money amount,
            Type type,
            ZonedDateTime paidDate,
            ValidationResult validation
    ) {
    }

    public record ValidationResult(
            String status,
            List<String> errors,
            String duplicateOf
    ) {
    }

    public record CategoryBreakdown(
            String targetCategory,
            String parentCategory,
            int transactionCount,
            Money totalAmount,
            Type type,
            boolean isNewCategory
    ) {
    }

    public record CategoryToCreate(
            String name,
            String parent,
            Type type
    ) {
    }

    public record MonthlyBreakdown(
            String month,
            Money inflowTotal,
            Money outflowTotal,
            int transactionCount
    ) {
    }
}
