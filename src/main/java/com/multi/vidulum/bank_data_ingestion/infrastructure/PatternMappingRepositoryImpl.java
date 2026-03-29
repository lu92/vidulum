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
    public Optional<PatternMapping> findUserByNormalizedPatternAndUserId(String normalizedPattern, String userId) {
        return mongoRepository.findUserByNormalizedPatternAndUserId(
                        normalizedPattern.toUpperCase().trim(),
                        userId
                )
                .map(PatternMappingEntity::toDomain);
    }

    @Override
    public Optional<PatternMapping> findUserByNormalizedPatternAndTypeAndUserId(
            String normalizedPattern,
            Type type,
            String userId
    ) {
        return mongoRepository.findUserByNormalizedPatternAndCategoryTypeAndUserId(
                        normalizedPattern.toUpperCase().trim(),
                        type,
                        userId
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
    public long deleteAllByUserId(String userId) {
        return mongoRepository.deleteAllByUserId(userId);
    }

    @Override
    public long countGlobal() {
        return mongoRepository.countGlobal();
    }

    @Override
    public long countByUserId(String userId) {
        return mongoRepository.countByUserId(userId);
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
