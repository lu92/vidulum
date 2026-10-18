package com.multi.vidulum.bank_data_adapter.domain;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for cached mapping rules.
 */
@Repository
public interface MappingRulesRepository extends MongoRepository<MappingRules, String> {

    /**
     * Find mapping rules by bank identifier (CSV structure hash).
     */
    Optional<MappingRules> findByBankIdentifier(String bankIdentifier);

    /**
     * Find all mapping rules ordered by usage count (most used first).
     */
    List<MappingRules> findAllByOrderByUsageCountDesc();

    /**
     * Find mapping rules by bank name (partial match).
     */
    List<MappingRules> findByBankNameContainingIgnoreCase(String bankName);

    /**
     * Check if mapping rules exist for a bank identifier.
     */
    boolean existsByBankIdentifier(String bankIdentifier);

    /**
     * Delete mapping rules by bank identifier.
     */
    void deleteByBankIdentifier(String bankIdentifier);
}
