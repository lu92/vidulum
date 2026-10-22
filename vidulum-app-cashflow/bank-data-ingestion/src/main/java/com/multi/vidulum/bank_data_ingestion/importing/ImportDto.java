package com.multi.vidulum.bank_data_ingestion.importing;

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
 * The HTTP shapes of the import job: start, progress, rollback, finalise, list.
 *
 * <p>Split out of the single 779-line {@code BankDataIngestionDto}, which was the last thing
 * left in the {@code app} package - and the last reason a section had to depend on it. That
 * package is gone now: every file in this module belongs to a named section.
 */
public class ImportDto {

    // ============ Import Job DTOs ============

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StartImportRequest {
        private String stagingSessionId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StartImportResponse {
        private String jobId;
        private String cashFlowId;
        private String stagingSessionId;
        private String status;
        private ImportInputJson input;
        private ImportProgressJson progress;
        private ImportResultJson result;
        private boolean canRollback;
        private String pollUrl;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportInputJson {
        private int totalTransactions;
        private int validTransactions;
        private int duplicateTransactions;
        private int categoriesToCreate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportProgressJson {
        private int percentage;
        private String currentPhase;
        private List<PhaseProgressJson> phases;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PhaseProgressJson {
        private String name;
        private String status;
        private int processed;
        private int total;
        private ZonedDateTime startedAt;
        private ZonedDateTime completedAt;
        private Long durationMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportResultJson {
        private List<String> categoriesCreated;
        private int transactionsImported;
        private int transactionsFailed;
        private List<ImportErrorJson> errors;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportErrorJson {
        private String bankTransactionId;
        private String error;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GetImportProgressResponse {
        private String jobId;
        private String cashFlowId;
        private String status;
        private ImportProgressJson progress;
        private ImportResultJson result;
        private ImportSummaryJson summary;
        private boolean canRollback;
        private ZonedDateTime rollbackDeadline;
        private long elapsedTimeMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportSummaryJson {
        private List<ImportCategoryBreakdownJson> categoryBreakdown;
        private List<ImportMonthlyBreakdownJson> monthlyBreakdown;
        private long totalDurationMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportCategoryBreakdownJson {
        private String categoryName;
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
    public static class ImportMonthlyBreakdownJson {
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
    public static class RollbackImportResponse {
        private String jobId;
        private String status;
        private RollbackSummaryJson rollbackSummary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RollbackSummaryJson {
        private int transactionsDeleted;
        private int categoriesDeleted;
        private long rollbackDurationMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FinalizeImportRequest {
        private boolean deleteMappings;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FinalizeImportResponse {
        private String jobId;
        private String status;
        private CleanupSummaryJson cleanup;
        private FinalSummaryJson finalSummary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CleanupSummaryJson {
        private long stagedTransactionsDeleted;
        private long mappingsDeleted;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FinalSummaryJson {
        private ZonedDateTime importedAt;
        private long totalDurationMs;
        private int categoriesCreated;
        private int transactionsImported;
        private List<ImportCategoryBreakdownJson> categoryBreakdown;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ListImportJobsResponse {
        private String cashFlowId;
        private List<ImportJobSummaryJson> jobs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportJobSummaryJson {
        private String jobId;
        private String status;
        private ZonedDateTime createdAt;
        private ZonedDateTime completedAt;
        private int transactionsImported;
        private int categoriesCreated;
        private boolean canRollback;
    }
}
