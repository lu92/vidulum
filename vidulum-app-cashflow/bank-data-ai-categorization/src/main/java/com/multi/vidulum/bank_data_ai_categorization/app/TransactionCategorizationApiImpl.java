package com.multi.vidulum.bank_data_ai_categorization.app;

import com.multi.vidulum.bank_data_ai_categorization.api.CategorizationSuggestions;
import com.multi.vidulum.bank_data_ai_categorization.api.CategorizationRequest;
import com.multi.vidulum.bank_data_ai_categorization.api.PatternMatches;
import com.multi.vidulum.bank_data_ai_categorization.api.PatternSource;
import com.multi.vidulum.bank_data_ai_categorization.api.PatternToLearn;
import com.multi.vidulum.bank_data_ai_categorization.api.TransactionCategorizationApi;
import com.multi.vidulum.bank_data_ai_categorization.api.TransactionToMatch;
import com.multi.vidulum.bank_data_ai_categorization.domain.PatternMapping;
import com.multi.vidulum.bank_data_ai_categorization.domain.PatternMappingRepository;
import com.multi.vidulum.common.CashFlowId;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * The module's answer to {@link TransactionCategorizationApi}, in process.
 *
 * <p>Package-private, like every other {@code *ModuleApiImpl} here: nobody outside this module names
 * the implementation - they inject the contract. {@code ModuleBoundariesArchTest} checks that, and
 * caught this class when it was first written public.
 *
 * <p>Thin on purpose: every method here is one call into something that already existed, and the
 * only judgement it makes is the one the contract promises - that learning the same pattern twice
 * updates it rather than storing a second copy.
 */
@Slf4j
@Component
@AllArgsConstructor
class TransactionCategorizationApiImpl implements TransactionCategorizationApi {

    private final AiCategorizationService categorizationService;
    private final LearnedPatternMatcher matcher;
    private final PatternMappingRepository patternMappingRepository;

    @Override
    public CategorizationSuggestions suggestFor(CategorizationRequest request) {
        return categorizationService.categorize(
                request.sessionId(),
                request.transactions(),
                request.cashFlowId().id(),
                request.categoryStructure(),
                request.detectedLanguage());
    }

    @Override
    public PatternMatches matchLearned(CashFlowId cashFlowId, List<TransactionToMatch> transactions) {
        return matcher.match(cashFlowId, transactions);
    }

    @Override
    public int learn(CashFlowId cashFlowId, List<PatternToLearn> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return 0;
        }

        int stored = 0;
        for (PatternToLearn pattern : patterns) {
            Optional<PatternMapping> known = patternMappingRepository
                    .findUserByNormalizedPatternAndTypeAndCashFlowId(
                            pattern.normalizedPattern().toUpperCase().trim(),
                            pattern.type(),
                            cashFlowId.id());

            if (known.isPresent()) {
                // the person confirmed it again, possibly against a different category: the latest
                // acceptance is the truth, and a second document for the same pattern would make
                // matching depend on which one was found first
                patternMappingRepository.save(known.get().updateCategory(
                        pattern.category(),
                        pattern.parentCategory(),
                        pattern.confidence()));
                continue;
            }

            patternMappingRepository.save(asNewPattern(cashFlowId, pattern));
            stored++;
        }

        log.info("Learned {} new patterns for cash flow {} ({} updated what was already known)",
                stored, cashFlowId.id(), patterns.size() - stored);
        return stored;
    }

    @Override
    public long forget(CashFlowId cashFlowId) {
        long forgotten = patternMappingRepository.deleteAllByCashFlowId(cashFlowId.id());
        log.info("Forgot {} patterns for cash flow {}", forgotten, cashFlowId.id());
        return forgotten;
    }

    private static PatternMapping asNewPattern(CashFlowId cashFlowId, PatternToLearn pattern) {
        return pattern.source() == PatternSource.AI
                ? PatternMapping.createAi(
                        pattern.normalizedPattern(), pattern.category(), pattern.parentCategory(),
                        pattern.type(), null, cashFlowId.id(), pattern.confidence())
                : PatternMapping.createUser(
                        pattern.normalizedPattern(), pattern.category(), pattern.parentCategory(),
                        pattern.type(), null, cashFlowId.id(), pattern.confidence());
    }
}
