package com.multi.vidulum.bank_data_ingestion.app;

import com.multi.vidulum.bank_data_ingestion.domain.MappingAction;
import com.multi.vidulum.cashflow.domain.Type;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * DTOs for Bank Data Ingestion REST API.
 */
public class BankDataIngestionDto {

    // ============ Request DTOs ============

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConfigureMappingsRequest {
        private List<MappingConfigJson> mappings;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MappingConfigJson {
        private String bankCategoryName;
        private MappingAction action;
        private String targetCategoryName;
        private String parentCategoryName;
        private Type categoryType;
    }

    // ============ Response DTOs ============

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConfigureMappingsResponse {
        private String cashFlowId;
        private int mappingsConfigured;
        private List<MappingResultJson> mappings;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MappingResultJson {
        private String mappingId;
        private String bankCategoryName;
        private String targetCategoryName;
        private String parentCategoryName;
        private Type categoryType;
        private MappingAction action;
        private String status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GetMappingsResponse {
        private String cashFlowId;
        private int mappingsCount;
        private List<MappingJson> mappings;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MappingJson {
        private String mappingId;
        private String bankCategoryName;
        private String targetCategoryName;
        private String parentCategoryName;
        private Type categoryType;
        private MappingAction action;
        private ZonedDateTime createdAt;
        private ZonedDateTime updatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeleteMappingResponse {
        private boolean deleted;
        private String mappingId;
        private String bankCategoryName;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeleteAllMappingsResponse {
        private boolean deleted;
        private long deletedCount;
    }

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
        private boolean isNewCategory;
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
        private boolean isNewCategory;
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
}
