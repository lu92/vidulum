package com.multi.vidulum.bank_data_ai_categorization.domain;

import com.multi.vidulum.bank_data_ai_categorization.api.PatternSource;
import com.multi.vidulum.cashflow.api.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The module's memory of patterns, in a list.
 *
 * <p>Exists so the contract test can run against the real implementation without a Mongo: what the
 * contract promises - whole-word matching, isolation per cash flow and per side, learning that
 * corrects rather than duplicates - is decided in the engine, not in the database.
 */
public class InMemoryPatternMappingRepository implements PatternMappingRepository {

    private final List<PatternMapping> stored = new ArrayList<>();

    @Override
    public PatternMapping save(PatternMapping patternMapping) {
        stored.removeIf(p -> p.id().equals(patternMapping.id()));
        stored.add(patternMapping);
        return patternMapping;
    }

    @Override
    public List<PatternMapping> saveAll(List<PatternMapping> patternMappings) {
        patternMappings.forEach(this::save);
        return patternMappings;
    }

    @Override
    public Optional<PatternMapping> findById(PatternMappingId id) {
        return stored.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    @Override
    public Optional<PatternMapping> findGlobalByNormalizedPattern(String normalizedPattern) {
        return stored.stream()
                .filter(p -> p.source() == PatternSource.GLOBAL)
                .filter(p -> p.normalizedPattern().equals(normalizedPattern))
                .findFirst();
    }

    @Override
    public Optional<PatternMapping> findGlobalByNormalizedPatternAndType(String normalizedPattern, Type type) {
        return findGlobalByNormalizedPattern(normalizedPattern).filter(p -> p.categoryType() == type);
    }

    @Override
    public Optional<PatternMapping> findUserByNormalizedPatternAndTypeAndCashFlowId(
            String normalizedPattern, Type type, String cashFlowId) {
        return stored.stream()
                .filter(p -> Objects.equals(p.cashFlowId(), cashFlowId))
                .filter(p -> p.normalizedPattern().equals(normalizedPattern))
                .filter(p -> p.categoryType() == type)
                .findFirst();
    }

    @Override
    public List<PatternMapping> findAllGlobal() {
        return stored.stream().filter(p -> p.source() == PatternSource.GLOBAL).toList();
    }

    @Override
    public List<PatternMapping> findAllByCashFlowId(String cashFlowId) {
        return stored.stream().filter(p -> Objects.equals(p.cashFlowId(), cashFlowId)).toList();
    }

    @Override
    public List<PatternMapping> findByCashFlowIdWithIntendedParent(String cashFlowId) {
        return findAllByCashFlowId(cashFlowId).stream()
                .filter(p -> p.intendedParentCategory() != null)
                .toList();
    }

    @Override
    public List<PatternMapping> findAllByUserId(String userId) {
        return stored.stream().filter(p -> Objects.equals(p.userId(), userId)).toList();
    }

    @Override
    public List<PatternMapping> findAllBySource(PatternSource source) {
        return stored.stream().filter(p -> p.source() == source).toList();
    }

    @Override
    public void deleteById(PatternMappingId id) {
        stored.removeIf(p -> p.id().equals(id));
    }

    @Override
    public long deleteAllUserPatterns() {
        return removeMatching(p -> p.source() == PatternSource.USER);
    }

    @Override
    public long deleteAllByCashFlowId(String cashFlowId) {
        return removeMatching(p -> Objects.equals(p.cashFlowId(), cashFlowId));
    }

    @Override
    public long countGlobal() {
        return findAllGlobal().size();
    }

    @Override
    public long countByCashFlowId(String cashFlowId) {
        return findAllByCashFlowId(cashFlowId).size();
    }

    @Override
    public boolean existsGlobalByNormalizedPattern(String normalizedPattern) {
        return findGlobalByNormalizedPattern(normalizedPattern).isPresent();
    }

    @Override
    public void recordUsage(PatternMappingId id) {
        findById(id).ifPresent(p -> save(p.recordUsage()));
    }

    private long removeMatching(java.util.function.Predicate<PatternMapping> predicate) {
        List<PatternMapping> doomed = stored.stream().filter(predicate).toList();
        stored.removeAll(doomed);
        return doomed.size();
    }
}
