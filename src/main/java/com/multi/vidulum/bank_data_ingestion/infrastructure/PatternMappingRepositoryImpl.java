package com.multi.vidulum.bank_data_ingestion.infrastructure;

import com.multi.vidulum.bank_data_ingestion.domain.PatternMapping;
import com.multi.vidulum.bank_data_ingestion.domain.PatternMappingId;
import com.multi.vidulum.bank_data_ingestion.domain.PatternMappingRepository;
import com.multi.vidulum.bank_data_ingestion.domain.PatternSource;
import com.multi.vidulum.bank_data_ingestion.infrastructure.entity.PatternMappingEntity;
import com.multi.vidulum.cashflow.domain.Type;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Implementation of PatternMappingRepository using MongoDB.
 *
 * USER patterns are isolated per CashFlow.
 * GLOBAL patterns are currently disabled but implementation kept for future use.
 */
@Repository
@RequiredArgsConstructor
public class PatternMappingRepositoryImpl implements PatternMappingRepository {

    private final PatternMappingMongoRepository mongoRepository;

    @Override
    public PatternMapping save(PatternMapping patternMapping) {
        PatternMappingEntity entity = PatternMappingEntity.fromDomain(patternMapping);
        PatternMappingEntity saved = mongoRepository.save(entity);
        return saved.toDomain();
    }

    @Override
    public List<PatternMapping> saveAll(List<PatternMapping> patternMappings) {
        List<PatternMappingEntity> entities = patternMappings.stream()
                .map(PatternMappingEntity::fromDomain)
                .toList();
        List<PatternMappingEntity> saved = mongoRepository.saveAll(entities);
        return saved.stream()
                .map(PatternMappingEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<PatternMapping> findById(PatternMappingId id) {
        return mongoRepository.findById(id.id())
                .map(PatternMappingEntity::toDomain);
    }

    @Override
    public Optional<PatternMapping> findGlobalByNormalizedPattern(String normalizedPattern) {
        return mongoRepository.findGlobalByNormalizedPattern(normalizedPattern.toUpperCase().trim())
                .map(PatternMappingEntity::toDomain);
    }

    @Override
    public Optional<PatternMapping> findGlobalByNormalizedPatternAndType(String normalizedPattern, Type type) {
        return mongoRepository.findGlobalByNormalizedPatternAndCategoryType(
                        normalizedPattern.toUpperCase().trim(),
                        type
                )
                .map(PatternMappingEntity::toDomain);
    }

    @Override
    public Optional<PatternMapping> findUserByNormalizedPatternAndTypeAndCashFlowId(
            String normalizedPattern,
            Type type,
            String cashFlowId
    ) {
        return mongoRepository.findUserByNormalizedPatternAndCategoryTypeAndCashFlowId(
                        normalizedPattern.toUpperCase().trim(),
                        type,
                        cashFlowId
                )
                .map(PatternMappingEntity::toDomain);
    }

    @Override
    public List<PatternMapping> findAllGlobal() {
        return mongoRepository.findAllGlobal().stream()
                .map(PatternMappingEntity::toDomain)
                .toList();
    }

    @Override
    public List<PatternMapping> findAllByCashFlowId(String cashFlowId) {
        return mongoRepository.findAllByCashFlowId(cashFlowId).stream()
                .map(PatternMappingEntity::toDomain)
                .toList();
    }

    @Override
    public List<PatternMapping> findByCashFlowIdWithIntendedParent(String cashFlowId) {
        return mongoRepository.findByCashFlowIdAndIntendedParentCategoryNotNull(cashFlowId).stream()
                .map(PatternMappingEntity::toDomain)
                .toList();
    }

    @Override
    public List<PatternMapping> findAllByUserId(String userId) {
        return mongoRepository.findAllByUserId(userId).stream()
                .map(PatternMappingEntity::toDomain)
                .toList();
    }

    @Override
    public List<PatternMapping> findAllBySource(PatternSource source) {
        return mongoRepository.findAllBySource(source).stream()
                .map(PatternMappingEntity::toDomain)
                .toList();
    }

    @Override
    public void deleteById(PatternMappingId id) {
        mongoRepository.deleteById(id.id());
    }

    @Override
    public long deleteAllUserPatterns() {
        return mongoRepository.deleteAllUserPatterns();
    }

    @Override
    public long deleteAllByCashFlowId(String cashFlowId) {
        return mongoRepository.deleteAllByCashFlowId(cashFlowId);
    }

    @Override
    public long countGlobal() {
        return mongoRepository.countGlobal();
    }

    @Override
    public long countByCashFlowId(String cashFlowId) {
        return mongoRepository.countByCashFlowId(cashFlowId);
    }

    @Override
    public boolean existsGlobalByNormalizedPattern(String normalizedPattern) {
        return mongoRepository.existsGlobalByNormalizedPattern(normalizedPattern.toUpperCase().trim());
    }

    @Override
    public void recordUsage(PatternMappingId id) {
        mongoRepository.incrementUsageCount(id.id(), Instant.now());
    }
}
