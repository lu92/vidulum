package com.multi.vidulum.bank_data_ingestion.staging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.multi.vidulum.bank_data_ingestion.canonical.PaymentMethod;
import com.multi.vidulum.bank_data_ingestion.canonical.TransactionClassification;
import com.multi.vidulum.cashflow.api.Type;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * The HTTP shapes of the staging section: stage a batch, preview it, list sessions, revalidate,
 * force the remainder to Uncategorized - and the CSV upload, whose response carries staging's own
 * result.
 *
 * <p>Split out of the single 779-line {@code BankDataIngestionDto}, which was the last thing
 * left in the {@code app} package - and the last reason a section had to depend on it. That
 * package is gone now: every file in this module belongs to a named section.
 */
public class StagingDto {

    // ============ Staging DTOs ============

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StageTransactionsRequest {
        private List<BankTransactionJson> transactions;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BankTransactionJson {
        private String bankTransactionId;
        private String name;
        private String description;
        private String bankCategory;
        private double amount;
        private String currency;
        private Type type;
        private ZonedDateTime paidDate;
        private String merchant;
        private Double merchantConfidence;
        private String counterpartyAccount;
        private PaymentMethod paymentMethod;
        private TransactionClassification classification;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StageTransactionsResponse {
        private String stagingSessionId;
        private String cashFlowId;
        private String status;
        private ZonedDateTime expiresAt;
        private StagingSummaryJson summary;
        private List<CategoryBreakdownJson> categoryBreakdown;
        private List<CategoryToCreateJson> categoriesToCreate;
        private List<MonthlyBreakdownJson> monthlyBreakdown;
        private List<DuplicateInfoJson> duplicates;
        private List<UnmappedCategoryJson> unmappedCategories;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StagingSummaryJson {
        private int totalTransactions;
        private int validTransactions;
        private int invalidTransactions;
        private int duplicateTransactions;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryBreakdownJson {
        private String targetCategory;
        private String parentCategory;
        private int transactionCount;
        private double totalAmount;
        private String currency;
        private Type type;
        private Boolean isNewCategory;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryToCreateJson {
        private String name;
        private String parent;
        private Type type;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthlyBreakdownJson {
        private String month;
        private double inflowTotal;
        private double outflowTotal;
        private String currency;
        private int transactionCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DuplicateInfoJson {
        private String bankTransactionId;
        private String name;
        private String duplicateOf;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UnmappedCategoryJson {
        private String bankCategory;
        private int count;
        private Type type;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GetStagingPreviewResponse {
        private String stagingSessionId;
        private String cashFlowId;
        private String status;
        private ZonedDateTime expiresAt;
        private StagingSummaryJson summary;
        private List<StagedTransactionPreviewJson> transactions;
        private List<CategoryBreakdownJson> categoryBreakdown;
        private List<CategoryToCreateJson> categoriesToCreate;
        private List<MonthlyBreakdownJson> monthlyBreakdown;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StagedTransactionPreviewJson {
        private String stagedTransactionId;
        private String bankTransactionId;
        private String name;
        private String description;
        private String bankCategory;
        private String targetCategory;
        private String parentCategory;
        private double amount;
        private String currency;
        private Type type;
        private ZonedDateTime paidDate;
        private ValidationResultJson validation;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationResultJson {
        private String status;
        private List<String> errors;
        private String duplicateOf;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeleteStagingSessionResponse {
        private String cashFlowId;
        private String stagingSessionId;
        private boolean deleted;
        private long deletedCount;
    }

    // ============ Upload CSV DTOs ============

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UploadCsvResponse {
        private ParseSummaryJson parseSummary;
        private StageTransactionsResponse stagingResult;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParseSummaryJson {
        private int totalRows;
        private int successfulRows;
        private int failedRows;
        private List<ParseErrorJson> errors;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParseErrorJson {
        private int rowNumber;
        private String message;
    }

    // ============ List Staging Sessions DTOs ============

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ListStagingSessionsResponse {
        private String cashFlowId;
        private List<StagingSessionSummaryJson> stagingSessions;
        private boolean hasPendingImport;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StagingSessionSummaryJson {
        private String stagingSessionId;
        private String status;
        private ZonedDateTime createdAt;
        private ZonedDateTime expiresAt;
        private StagingSessionCountsJson counts;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StagingSessionCountsJson {
        private int totalTransactions;
        private int validTransactions;
        private int invalidTransactions;
        private int duplicateTransactions;
    }

    // ============ Revalidate Staging DTOs ============

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RevalidateStagingResponse {
        private String stagingSessionId;
        private String cashFlowId;
        private String status;
        private RevalidationSummaryJson summary;
        private List<String> stillUnmappedCategories;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RevalidationSummaryJson {
        private int totalTransactions;
        private int revalidatedCount;
        private int stillPendingCount;
        private int validCount;
        private int invalidCount;
        private int duplicateCount;
    }

    // ============ Force Uncategorized DTOs ============

    /**
     * Response from forcing pending transactions to use "Uncategorized" category.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ForceUncategorizedResponse {
        private String cashFlowId;
        private String stagingSessionId;
        private String status;
        private int transactionsUpdated;
        private boolean categoryCreated;
        private ForceUncategorizedValidationSummaryJson validationSummary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ForceUncategorizedValidationSummaryJson {
        private int totalTransactions;
        private int validTransactions;
        private int invalidTransactions;
        private int duplicateTransactions;
        private boolean readyForImport;
    }
}
