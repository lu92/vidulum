package com.multi.vidulum.bank_data_ingestion.domain;

import com.multi.vidulum.cashflow.domain.Type;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for PatternMapping persistence.
 *
 * USER patterns are isolated per CashFlow - each CashFlow has its own set of learned patterns.
 * GLOBAL patterns are currently disabled but kept for future use.
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
     * NOTE: GLOBAL patterns are currently disabled.
     */
    Optional<PatternMapping> findGlobalByNormalizedPattern(String normalizedPattern);

    /**
     * Finds a GLOBAL pattern by normalized pattern and type.
     * NOTE: GLOBAL patterns are currently disabled.
     */
    Optional<PatternMapping> findGlobalByNormalizedPatternAndType(String normalizedPattern, Type type);

    /**
     * Finds a USER pattern by normalized pattern, type, and CashFlow ID.
     * This is the primary lookup method for per-CashFlow pattern matching.
     */
    Optional<PatternMapping> findUserByNormalizedPatternAndTypeAndCashFlowId(
            String normalizedPattern,
            Type type,
            String cashFlowId
    );

    /**
     * Finds all GLOBAL patterns.
     * NOTE: GLOBAL patterns are currently disabled.
     */
    List<PatternMapping> findAllGlobal();

    /**
     * Finds all USER patterns for a specific CashFlow.
     */
    List<PatternMapping> findAllByCashFlowId(String cashFlowId);

    /**
     * Finds all USER patterns for a specific user (across all CashFlows).
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
     * Deletes all USER patterns (non-GLOBAL).
     * Used during application startup to clear learned patterns.
     */
    long deleteAllUserPatterns();

    /**
     * Deletes all patterns for a specific CashFlow.
     */
    long deleteAllByCashFlowId(String cashFlowId);

    /**
     * Counts all GLOBAL patterns.
     */
    long countGlobal();

    /**
     * Counts all patterns for a specific CashFlow.
     */
    long countByCashFlowId(String cashFlowId);

    /**
     * Checks if a GLOBAL pattern exists.
     */
    boolean existsGlobalByNormalizedPattern(String normalizedPattern);

    /**
     * Increments usage count and updates lastUsedAt for a pattern.
     */
    void recordUsage(PatternMappingId id);
}
