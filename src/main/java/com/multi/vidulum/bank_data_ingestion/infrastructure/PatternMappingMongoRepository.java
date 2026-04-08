package com.multi.vidulum.bank_data_ingestion.infrastructure;

import com.multi.vidulum.bank_data_ingestion.domain.PatternSource;
import com.multi.vidulum.bank_data_ingestion.infrastructure.entity.PatternMappingEntity;
import com.multi.vidulum.cashflow.domain.Type;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data MongoDB repository for PatternMappingEntity.
 *
 * USER patterns are isolated per CashFlow.
 * GLOBAL patterns are currently disabled but queries kept for future use.
 */
@Repository
public interface PatternMappingMongoRepository extends MongoRepository<PatternMappingEntity, String> {

    /**
     * Find GLOBAL pattern by normalized pattern string.
     * NOTE: GLOBAL patterns are currently disabled.
     */
    @Query("{ 'normalizedPattern': ?0, 'source': 'GLOBAL' }")
    Optional<PatternMappingEntity> findGlobalByNormalizedPattern(String normalizedPattern);

    /**
     * Find GLOBAL pattern by normalized pattern and category type.
     * NOTE: GLOBAL patterns are currently disabled.
     */
    @Query("{ 'normalizedPattern': ?0, 'categoryType': ?1, 'source': 'GLOBAL' }")
    Optional<PatternMappingEntity> findGlobalByNormalizedPatternAndCategoryType(
            String normalizedPattern,
            Type categoryType
    );

    /**
     * Find USER pattern by normalized pattern, type, and CashFlow ID.
     * Primary lookup method for per-CashFlow pattern matching.
     */
    @Query("{ 'normalizedPattern': ?0, 'categoryType': ?1, 'cashFlowId': ?2, 'source': 'USER' }")
    Optional<PatternMappingEntity> findUserByNormalizedPatternAndCategoryTypeAndCashFlowId(
            String normalizedPattern,
            Type categoryType,
            String cashFlowId
    );

    /**
     * Find all GLOBAL patterns.
     * NOTE: GLOBAL patterns are currently disabled.
     */
    @Query("{ 'source': 'GLOBAL' }")
    List<PatternMappingEntity> findAllGlobal();

    /**
     * Find all patterns by CashFlow ID.
     */
    List<PatternMappingEntity> findAllByCashFlowId(String cashFlowId);

    /**
     * Find patterns by CashFlow ID that have intendedParentCategory set.
     * Used to provide hierarchy hints to AI during subsequent imports.
     */
    @Query("{ 'cashFlowId': ?0, 'intendedParentCategory': { '$ne': null } }")
    List<PatternMappingEntity> findByCashFlowIdAndIntendedParentCategoryNotNull(String cashFlowId);

    /**
     * Find all patterns by user ID (across all CashFlows).
     */
    List<PatternMappingEntity> findAllByUserId(String userId);

    /**
     * Find all patterns by source.
     */
    List<PatternMappingEntity> findAllBySource(PatternSource source);

    /**
     * Delete all USER patterns (non-GLOBAL).
     * Used during application startup to clear learned patterns.
     */
    @Query(value = "{ 'source': { '$ne': 'GLOBAL' } }", delete = true)
    long deleteAllUserPatterns();

    /**
     * Delete all patterns by CashFlow ID.
     */
    long deleteAllByCashFlowId(String cashFlowId);

    /**
     * Count GLOBAL patterns.
     */
    @Query(value = "{ 'source': 'GLOBAL' }", count = true)
    long countGlobal();

    /**
     * Count patterns by CashFlow ID.
     */
    long countByCashFlowId(String cashFlowId);

    /**
     * Check if GLOBAL pattern exists.
     */
    @Query(value = "{ 'normalizedPattern': ?0, 'source': 'GLOBAL' }", exists = true)
    boolean existsGlobalByNormalizedPattern(String normalizedPattern);

    /**
     * Increment usage count and update lastUsedAt.
     */
    @Query("{ '_id': ?0 }")
    @Update("{ '$inc': { 'usageCount': 1 }, '$set': { 'lastUsedAt': ?1 } }")
    void incrementUsageCount(String id, java.time.Instant lastUsedAt);
}
