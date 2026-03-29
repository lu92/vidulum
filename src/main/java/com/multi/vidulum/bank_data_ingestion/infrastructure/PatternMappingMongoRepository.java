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
 */
@Repository
public interface PatternMappingMongoRepository extends MongoRepository<PatternMappingEntity, String> {

    /**
     * Find GLOBAL pattern by normalized pattern string.
     */
    @Query("{ 'normalizedPattern': ?0, 'source': 'GLOBAL' }")
    Optional<PatternMappingEntity> findGlobalByNormalizedPattern(String normalizedPattern);

    /**
     * Find GLOBAL pattern by normalized pattern and category type.
     */
    @Query("{ 'normalizedPattern': ?0, 'categoryType': ?1, 'source': 'GLOBAL' }")
    Optional<PatternMappingEntity> findGlobalByNormalizedPatternAndCategoryType(
            String normalizedPattern,
            Type categoryType
    );

    /**
     * Find USER pattern by normalized pattern and user ID.
     */
    @Query("{ 'normalizedPattern': ?0, 'userId': ?1, 'source': 'USER' }")
    Optional<PatternMappingEntity> findUserByNormalizedPatternAndUserId(
            String normalizedPattern,
            String userId
    );

    /**
     * Find USER pattern by normalized pattern, type, and user ID.
     */
    @Query("{ 'normalizedPattern': ?0, 'categoryType': ?1, 'userId': ?2, 'source': 'USER' }")
    Optional<PatternMappingEntity> findUserByNormalizedPatternAndCategoryTypeAndUserId(
            String normalizedPattern,
            Type categoryType,
            String userId
    );

    /**
     * Find all GLOBAL patterns.
     */
    @Query("{ 'source': 'GLOBAL' }")
    List<PatternMappingEntity> findAllGlobal();

    /**
     * Find all patterns by user ID.
     */
    List<PatternMappingEntity> findAllByUserId(String userId);

    /**
     * Find all patterns by source.
     */
    List<PatternMappingEntity> findAllBySource(PatternSource source);

    /**
     * Delete all patterns by user ID.
     */
    long deleteAllByUserId(String userId);

    /**
     * Count GLOBAL patterns.
     */
    @Query(value = "{ 'source': 'GLOBAL' }", count = true)
    long countGlobal();

    /**
     * Count patterns by user ID.
     */
    long countByUserId(String userId);

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
