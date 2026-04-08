package com.multi.vidulum.bank_data_ingestion.infrastructure.entity;

import com.multi.vidulum.bank_data_ingestion.domain.PatternMapping;
import com.multi.vidulum.bank_data_ingestion.domain.PatternMappingId;
import com.multi.vidulum.bank_data_ingestion.domain.PatternSource;
import com.multi.vidulum.cashflow.domain.Type;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * MongoDB entity for PatternMapping.
 *
 * USER patterns are isolated per CashFlow via cashFlowId.
 * GLOBAL patterns have null cashFlowId and userId.
 */
@Document(collection = "pattern_mappings")
@CompoundIndex(name = "global_pattern_idx", def = "{'normalizedPattern': 1, 'source': 1}", unique = false)
@CompoundIndex(name = "user_pattern_idx", def = "{'normalizedPattern': 1, 'userId': 1, 'cashFlowId': 1, 'source': 1}", unique = false)
@CompoundIndex(name = "cashflow_pattern_idx", def = "{'cashFlowId': 1, 'normalizedPattern': 1, 'categoryType': 1}", unique = false)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PatternMappingEntity {

    @Id
    private String id;

    @Indexed
    private String normalizedPattern;

    private String suggestedCategory;
    private String intendedParentCategory;  // AI's original hierarchy intent (nullable)
    private Type categoryType;

    @Indexed
    private PatternSource source;

    @Indexed
    private String userId;

    @Indexed
    private String cashFlowId;

    private int usageCount;
    private double confidenceScore;
    private Instant createdAt;
    private Instant lastUsedAt;

    /**
     * Converts domain PatternMapping to entity.
     */
    public static PatternMappingEntity fromDomain(PatternMapping domain) {
        return new PatternMappingEntity(
                domain.id().id(),
                domain.normalizedPattern(),
                domain.suggestedCategory(),
                domain.intendedParentCategory(),
                domain.categoryType(),
                domain.source(),
                domain.userId(),
                domain.cashFlowId(),
                domain.usageCount(),
                domain.confidenceScore(),
                domain.createdAt(),
                domain.lastUsedAt()
        );
    }

    /**
     * Converts entity to domain PatternMapping.
     */
    public PatternMapping toDomain() {
        return new PatternMapping(
                PatternMappingId.of(id),
                normalizedPattern,
                suggestedCategory,
                intendedParentCategory,
                categoryType,
                source,
                userId,
                cashFlowId,
                usageCount,
                confidenceScore,
                createdAt,
                lastUsedAt
        );
    }
}
