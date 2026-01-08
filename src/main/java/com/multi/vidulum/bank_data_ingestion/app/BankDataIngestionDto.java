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
}
