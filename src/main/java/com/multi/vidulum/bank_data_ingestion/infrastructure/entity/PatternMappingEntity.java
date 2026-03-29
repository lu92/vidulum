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
 */
@Document(collection = "pattern_mappings")
@CompoundIndex(name = "global_pattern_idx", def = "{'normalizedPattern': 1, 'source': 1}", unique = false)
@CompoundIndex(name = "user_pattern_idx", def = "{'normalizedPattern': 1, 'userId': 1, 'source': 1}", unique = false)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PatternMappingEntity {

    @Id
    private String id;

    @Indexed
    private String normalizedPattern;

    private String suggestedCategory;
    private String parentCategory;
    private Type categoryType;

    @Indexed
    private PatternSource source;

    @Indexed
    private String userId;

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
                domain.parentCategory(),
                domain.categoryType(),
                domain.source(),
                domain.userId(),
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
                parentCategory,
                categoryType,
                source,
                userId,
                usageCount,
                confidenceScore,
                createdAt,
                lastUsedAt
        );
    }
}
