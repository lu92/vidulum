package com.multi.vidulum.bank_data_ingestion.mappings;

import com.multi.vidulum.bank_data_ingestion.mappings.domain.MappingAction;
import com.multi.vidulum.cashflow.api.Type;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * The HTTP shapes of the mappings section.
 *
 * <p><b>Split out of the shared DTO because a rule said so, not because it looked tidier.</b> While
 * these eight types lived in {@code app}, the mappings controller depended on {@code app} and
 * staging's handlers - still in {@code app} - depended on mappings' repository, so
 * {@code CanonicalRowIsSelfContainedTest} reported a cycle between the two slices. It was right: a
 * section whose request and response types belong to a neighbour is not a section.
 *
 * <p>The rest of {@code BankDataIngestionDto} stays shared <b>for now</b>, and that is measured
 * rather than assumed: {@code importing} and {@code classification} read it too, but nothing in
 * {@code app} depends on <em>them</em>, so there is no cycle to break there today. When staging is
 * extracted and {@code app} disappears, what is left of that class splits along the same lines.
 */
public class MappingsDto {

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
