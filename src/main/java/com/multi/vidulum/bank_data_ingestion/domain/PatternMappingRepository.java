package com.multi.vidulum.bank_data_ingestion.domain;

import com.multi.vidulum.cashflow.domain.Type;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for PatternMapping persistence.
 */
public interface PatternMappingRepository {

    /**
     * Saves a pattern mapping.
     */
    PatternMapping save(PatternMapping patternMapping);

    /**
     * Saves multiple pattern mappings.
     */
    List<PatternMapping> saveAll(List<PatternMapping> patternMappings);

    /**
     * Finds a pattern mapping by its ID.
     */
    Optional<PatternMapping> findById(PatternMappingId id);

    /**
     * Finds a GLOBAL pattern by normalized pattern string.
     */
    Optional<PatternMapping> findGlobalByNormalizedPattern(String normalizedPattern);

    /**
     * Finds a GLOBAL pattern by normalized pattern and type.
     */
    Optional<PatternMapping> findGlobalByNormalizedPatternAndType(String normalizedPattern, Type type);

    /**
     * Finds a USER pattern by normalized pattern and user ID.
     */
    Optional<PatternMapping> findUserByNormalizedPatternAndUserId(String normalizedPattern, String userId);

    /**
     * Finds a USER pattern by normalized pattern, type, and user ID.
     */
    Optional<PatternMapping> findUserByNormalizedPatternAndTypeAndUserId(
            String normalizedPattern,
            Type type,
            String userId
    );

    /**
     * Finds all GLOBAL patterns.
     */
    List<PatternMapping> findAllGlobal();

    /**
     * Finds all USER patterns for a specific user.
     */
    List<PatternMapping> findAllByUserId(String userId);

    /**
     * Finds all patterns by source.
     */
    List<PatternMapping> findAllBySource(PatternSource source);

    /**
     * Deletes a pattern mapping by ID.
     */
    void deleteById(PatternMappingId id);

    /**
     * Deletes all USER patterns for a specific user.
     */
    long deleteAllByUserId(String userId);

    /**
     * Counts all GLOBAL patterns.
     */
    long countGlobal();

    /**
     * Counts all USER patterns for a specific user.
     */
    long countByUserId(String userId);

    /**
     * Checks if a GLOBAL pattern exists.
     */
    boolean existsGlobalByNormalizedPattern(String normalizedPattern);

    /**
     * Increments usage count and updates lastUsedAt for a pattern.
     */
    void recordUsage(PatternMappingId id);
}
