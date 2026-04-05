package com.multi.vidulum.bank_data_ingestion.app.categorization;

import com.multi.vidulum.bank_data_ingestion.domain.*;
import com.multi.vidulum.cashflow.domain.Type;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for AI-powered transaction categorization.
 *
 * Flow:
 * 1. Load staged transactions from session
 * 2. Normalize and deduplicate into patterns
 * 3. Check USER cache (per CashFlow) for known patterns
 * 4. Send remaining patterns to AI
 * 5. Combine results and return suggestions
 *
 * Cost optimization:
 * - Pattern deduplication reduces AI input (402 txns → 45 patterns)
 * - Cache hits are FREE
 * - Only uncached patterns sent to AI
 *
 * Note: GLOBAL patterns are currently disabled. Each CashFlow has its own
 * isolated cache of USER patterns to avoid cross-CashFlow category mismatches.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiCategorizationService {

    private final ChatModel chatModel;
    private final TransactionNameNormalizer normalizer;
    private final PatternDeduplicator deduplicator;
    private final PatternMappingRepository patternMappingRepository;
    private final AiCategorizationPromptBuilder promptBuilder;
    private final AiCategorizationResponseParser responseParser;

    @Value("${vidulum.ai.categorization.enabled:true}")
    private boolean aiEnabled;

    @Value("${vidulum.ai.categorization.max-patterns:100}")
    private int maxPatternsToAi;

    // GLOBAL patterns are disabled - they caused issues with language mismatch
    // (English patterns couldn't match Polish categories in CashFlow)
    private static final boolean GLOBAL_CACHE_ENABLED = false;

    /**
     * Categorizes transactions in a staging session.
     *
     * @param sessionId          the staging session ID
     * @param transactions       the staged transactions
     * @param cashFlowId         the CashFlow ID (for per-CashFlow pattern isolation)
     * @param categoryStructure  existing categories with type and hierarchy
     * @return categorization result with suggestions
     */
    public AiCategorizationResult categorize(
            StagingSessionId sessionId,
            List<StagedTransaction> transactions,
            String cashFlowId,
            ExistingCategoryStructure categoryStructure) {

        if (transactions == null || transactions.isEmpty()) {
            log.info("No transactions to categorize for session: {}", sessionId);
            return AiCategorizationResult.noPatterns(sessionId);
        }

        log.info("Starting AI categorization for session: {}, cashFlowId: {}, transactions: {}",
                sessionId, cashFlowId, transactions.size());

        // Step 1: Deduplicate into patterns
        List<PatternDeduplicator.PatternGroup> patternGroups = deduplicator.deduplicate(transactions);
        log.info("Deduplicated {} transactions into {} unique patterns",
                transactions.size(), patternGroups.size());

        if (patternGroups.isEmpty()) {
            return AiCategorizationResult.noPatterns(sessionId);
        }

        // Step 2: Check cache for each pattern (per CashFlow)
        List<AiCategorizationResult.PatternSuggestion> cachedSuggestions = new ArrayList<>();
        List<PatternDeduplicator.PatternGroup> uncachedPatterns = new ArrayList<>();

        int globalCacheHits = 0;
        int userCacheHits = 0;

        for (PatternDeduplicator.PatternGroup pg : patternGroups) {
            String normalizedPattern = pg.pattern();
            Type type = pg.type();

            // Check USER cache (per CashFlow)
            Optional<PatternMapping> userMapping = patternMappingRepository
                    .findUserByNormalizedPatternAndTypeAndCashFlowId(normalizedPattern, type, cashFlowId);

            if (userMapping.isPresent()) {
                cachedSuggestions.add(AiCategorizationResult.PatternSuggestion.fromCache(
                        userMapping.get(),
                        pg.sampleTransaction(),
                        pg.transactionCount(),
                        pg.totalAmount()
                ));
                patternMappingRepository.recordUsage(userMapping.get().id());
                userCacheHits++;
                continue;
            }

            // GLOBAL cache is disabled - patterns are per-CashFlow only
            if (GLOBAL_CACHE_ENABLED) {
                Optional<PatternMapping> globalMapping = patternMappingRepository
                        .findGlobalByNormalizedPatternAndType(normalizedPattern, type);

                if (globalMapping.isPresent()) {
                    cachedSuggestions.add(AiCategorizationResult.PatternSuggestion.fromCache(
                            globalMapping.get(),
                            pg.sampleTransaction(),
                            pg.transactionCount(),
                            pg.totalAmount()
                    ));
                    patternMappingRepository.recordUsage(globalMapping.get().id());
                    globalCacheHits++;
                    continue;
                }
            }

            // No cache hit - needs AI
            uncachedPatterns.add(pg);
        }

        log.info("Cache results for cashFlowId {}: {} user hits, {} need AI (GLOBAL disabled: {})",
                cashFlowId, userCacheHits, uncachedPatterns.size(), !GLOBAL_CACHE_ENABLED);

        // Step 3: Call AI for uncached patterns
        List<AiCategorizationResult.PatternSuggestion> aiSuggestions = new ArrayList<>();
        List<AiCategorizationResult.UnrecognizedPattern> unrecognizedPatterns = new ArrayList<>();
        AiCategorizationResult.SuggestedStructure structure =
                AiCategorizationResult.SuggestedStructure.empty();
        int tokensUsed = 0;

        if (!uncachedPatterns.isEmpty() && aiEnabled) {
            // Limit patterns to avoid huge prompts
            List<PatternDeduplicator.PatternGroup> patternsForAi = uncachedPatterns.stream()
                    .sorted(Comparator.comparingInt(PatternDeduplicator.PatternGroup::transactionCount).reversed())
                    .limit(maxPatternsToAi)
                    .toList();

            if (patternsForAi.size() < uncachedPatterns.size()) {
                log.warn("Truncated patterns for AI: {} → {} (max: {})",
                        uncachedPatterns.size(), patternsForAi.size(), maxPatternsToAi);
            }

            AiCallResult aiResult = callAi(patternsForAi, categoryStructure);

            if (aiResult.success) {
                structure = aiResult.structure;
                aiSuggestions = aiResult.suggestions;
                unrecognizedPatterns = aiResult.unrecognizedPatterns;
                tokensUsed = aiResult.tokensUsed;
            } else {
                log.warn("AI categorization failed: {}", aiResult.errorMessage);
            }
        } else if (!aiEnabled) {
            log.info("AI categorization disabled - returning cache results only");
        }

        // Step 4: Combine results
        List<AiCategorizationResult.PatternSuggestion> allSuggestions = new ArrayList<>();
        allSuggestions.addAll(cachedSuggestions);
        allSuggestions.addAll(aiSuggestions);

        // Sort by transaction count (most frequent first)
        allSuggestions.sort(Comparator.comparingInt(
                AiCategorizationResult.PatternSuggestion::transactionCount).reversed());

        // Calculate stats
        int autoAccepted = (int) allSuggestions.stream()
                .filter(AiCategorizationResult.PatternSuggestion::isAutoAccepted)
                .count();
        int suggested = (int) allSuggestions.stream()
                .filter(AiCategorizationResult.PatternSuggestion::needsConfirmation)
                .count();
        int needsManual = (int) allSuggestions.stream()
                .filter(s -> s.confidence() < 50)
                .count();

        // Calculate new stats: matchedExisting vs createdNew
        int matchedExisting = (int) allSuggestions.stream()
                .filter(AiCategorizationResult.PatternSuggestion::isExistingCategory)
                .count();
        int createdNew = (int) allSuggestions.stream()
                .filter(s -> !s.isExistingCategory())
                .count();
        int unrecognizedCount = unrecognizedPatterns.size();

        AiCategorizationResult.CategorizationStats stats = new AiCategorizationResult.CategorizationStats(
                patternGroups.size(),
                autoAccepted,
                suggested,
                needsManual,
                globalCacheHits,
                userCacheHits,
                aiSuggestions.size(),
                matchedExisting,
                createdNew,
                unrecognizedCount
        );

        AiCategorizationResult.AiCost cost = tokensUsed > 0
                ? AiCategorizationResult.AiCost.estimated(tokensUsed)
                : AiCategorizationResult.AiCost.free();

        log.info("Categorization complete: {} patterns, {} auto-accept, {} suggested, {} manual, {} existing, {} new, {} unrecognized, cost: {}",
                allSuggestions.size(), autoAccepted, suggested, needsManual, matchedExisting, createdNew, unrecognizedCount, cost.estimatedCost());

        return AiCategorizationResult.success(sessionId, structure, allSuggestions, unrecognizedPatterns, stats, cost);
    }

    /**
     * Calls AI with patterns and parses response.
     */
    private AiCallResult callAi(List<PatternDeduplicator.PatternGroup> patterns,
                                 ExistingCategoryStructure categoryStructure) {
        try {
            String systemPrompt = promptBuilder.getSystemPrompt();
            String userPrompt = promptBuilder.buildUserPrompt(patterns, categoryStructure);

            log.debug("AI prompt size: {} chars", userPrompt.length());

            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt)
            ));

            ChatResponse response = chatModel.call(prompt);
            String aiOutput = response.getResult().getOutput().getText();

            // Estimate tokens (rough: 4 chars per token)
            int tokensUsed = (systemPrompt.length() + userPrompt.length() + aiOutput.length()) / 4;

            log.debug("AI response size: {} chars, estimated tokens: {}", aiOutput.length(), tokensUsed);

            // Parse response
            AiCategorizationResponseParser.ParseResult parseResult =
                    responseParser.parse(aiOutput, patterns);

            if (parseResult.success()) {
                return new AiCallResult(
                        true,
                        parseResult.structure(),
                        parseResult.suggestions(),
                        parseResult.unrecognizedPatterns(),
                        tokensUsed,
                        null
                );
            } else {
                return new AiCallResult(false, null, List.of(), List.of(), 0, parseResult.errorMessage());
            }

        } catch (Exception e) {
            log.error("AI categorization error", e);
            return new AiCallResult(false, null, List.of(), List.of(), 0, e.getMessage());
        }
    }

    private record AiCallResult(
            boolean success,
            AiCategorizationResult.SuggestedStructure structure,
            List<AiCategorizationResult.PatternSuggestion> suggestions,
            List<AiCategorizationResult.UnrecognizedPattern> unrecognizedPatterns,
            int tokensUsed,
            String errorMessage
    ) {}
}
