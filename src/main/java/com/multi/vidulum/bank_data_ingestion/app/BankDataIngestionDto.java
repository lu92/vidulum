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
}
