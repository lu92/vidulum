package com.multi.vidulum.bank_data_ingestion.app.commands.stage_transactions;

import com.multi.vidulum.bank_data_ingestion.domain.StagingSessionId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.common.Money;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Result of staging transactions.
 */
public record StageTransactionsResult(
        StagingSessionId stagingSessionId,
        CashFlowId cashFlowId,
        StagingStatus status,
        ZonedDateTime expiresAt,
        StagingSummary summary,
        List<CategoryBreakdown> categoryBreakdown,
        List<CategoryToCreate> categoriesToCreate,
        List<MonthlyBreakdown> monthlyBreakdown,
        List<DuplicateInfo> duplicates,
        List<UnmappedCategory> unmappedCategories
) {

    public enum StagingStatus {
        READY_FOR_IMPORT,
        HAS_UNMAPPED_CATEGORIES,
        HAS_VALIDATION_ERRORS
    }

    public record StagingSummary(
            int totalTransactions,
            int validTransactions,
            int invalidTransactions,
            int duplicateTransactions
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

    public record DuplicateInfo(
            String bankTransactionId,
            String name,
            String duplicateOf
    ) {
    }

    public record UnmappedCategory(
            String bankCategory,
            int count,
            Type type
    ) {
    }
}
