package com.multi.vidulum.bank_data_adapter.app;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTOs for AI Bank CSV Adapter HTTP integration tests.
 */
public class AiBankCsvAdapterDto {

    // ============ Transform Responses ============

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransformResponse {
        private String transformationId;
        private boolean success;
        private String detectedBank;
        private String detectedLanguage;
        private String detectedCountry;
        private int rowCount;
        private List<String> warnings;
        private String errorCode;
        private String errorMessage;
        private long processingTimeMs;
        private boolean fromCache;
        private String bankIdentifier;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PreviewResponse {
        private String transformationId;
        private List<Map<String, String>> rows;
        private int totalRows;
        private int previewRows;
    }

    // ============ Import Operations ============

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportRequest {
        private String cashFlowId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportResponse {
        private String stagingSessionId;
        private String status;
        private int stagedTransactions;
        private int unmappedCategories;
    }

    // ============ History ============

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HistoryResponse {
        private List<TransformHistoryItem> transformations;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransformHistoryItem {
        private String transformationId;
        private String originalFileName;
        private String detectedBank;
        private int rowCount;
        private boolean success;
        private String importStatus;
        private ZonedDateTime createdAt;
        private ZonedDateTime importedAt;
        private String stagingSessionId;
    }

    // ============ Mapping Rules ============

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MappingRulesResponse {
        private String bankIdentifier;
        private String bankName;
        private String bankCountry;
        private List<ColumnMapping> columnMappings;
        private String dateFormat;
        private String delimiter;
        private String encoding;
        private int headerRowIndex;
        private int usageCount;
        private ZonedDateTime lastUsedAt;
        private ZonedDateTime createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ColumnMapping {
        private String sourceColumn;
        private String targetColumn;
        private String transformationType;
        private Map<String, String> transformationParams;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ListMappingRulesResponse {
        private List<MappingRulesSummary> rules;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MappingRulesSummary {
        private String bankIdentifier;
        private String bankName;
        private int usageCount;
        private ZonedDateTime lastUsedAt;
    }
}
