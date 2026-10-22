package com.multi.vidulum.bank_data_ingestion.classification;

import com.multi.vidulum.common.auth.AuthenticatedUserProvider;
import com.multi.vidulum.bank_data_ai_categorization.api.AiCategorizationResult;
import com.multi.vidulum.bank_data_ingestion.canonical.*;
import com.multi.vidulum.bank_data_ingestion.classification.commands.accept_ai_suggestions.AcceptAiSuggestionsCommand;
import com.multi.vidulum.bank_data_ingestion.classification.commands.accept_ai_suggestions.AcceptAiSuggestionsResult;
import com.multi.vidulum.bank_data_ingestion.classification.commands.ai_categorize.AiCategorizeCommand;
import com.multi.vidulum.bank_data_ingestion.staging.domain.*;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import java.util.List;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Classification over HTTP: ask for suggestions, accept them.

 * <p><b>The paths keep saying {@code ai-categorize} and {@code accept-ai} by decision of the code
 * owner</b> - the external surface stays put even as the inside is renamed to the capability rather
 * than the supplier (step 1 of F67). So for a while this controller's URLs will not match the names
 * of the commands they send, and that is deliberate rather than an oversight.

 * <p>Split out of the single 1,195-line controller, which is what removed the {@code app ->
 * classification} edge and with it the exemption the cycle rule needed.
 */
@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/bank-data-ingestion/cf={cashFlowId}")
public class ClassificationRestController {

    private final CommandGateway commandGateway;
    private final AuthenticatedUserProvider authenticatedUserProvider;

    /**
     * Trigger AI-powered categorization for a staging session.
     * Analyzes staged transactions and returns category structure and pattern mapping suggestions.
     */
    @PostMapping("/staging/{stagingSessionId}/ai-categorize")
    public ClassificationDto.AiCategorizeResponse aiCategorize(
            @PathVariable("cashFlowId") String cashFlowId,
            @PathVariable("stagingSessionId") String stagingSessionId) {

        AiCategorizationResult result = commandGateway.send(
                new AiCategorizeCommand(
                        CashFlowId.of(cashFlowId),
                        StagingSessionId.of(stagingSessionId),
                        getCurrentUserId()
                )
        );

        return toAiCategorizeResponse(result);
    }
    /**
     * Accept AI categorization suggestions.
     * Creates categories, applies mappings, and optionally caches patterns for future imports.
     */
    @PostMapping("/staging/{stagingSessionId}/accept-ai")
    public ClassificationDto.AcceptAiSuggestionsResponse acceptAiSuggestions(
            @PathVariable("cashFlowId") String cashFlowId,
            @PathVariable("stagingSessionId") String stagingSessionId,
            @RequestBody ClassificationDto.AcceptAiSuggestionsRequest request) {

        AcceptAiSuggestionsResult result = commandGateway.send(
                new AcceptAiSuggestionsCommand(
                        CashFlowId.of(cashFlowId),
                        StagingSessionId.of(stagingSessionId),
                        getCurrentUserId(),
                        toCategoriesToCreate(request.getAcceptedCategories()),
                        toMappingsToApply(request.getAcceptedMappings()),
                        toBankCategoryMappingsToApply(request.getAcceptedBankCategoryMappings()),
                        request.isSaveToCache()
                )
        );

        return toAcceptAiSuggestionsResponse(result);
    }
    private ClassificationDto.AiCategorizeResponse toAiCategorizeResponse(AiCategorizationResult result) {
        return ClassificationDto.AiCategorizeResponse.builder()
                .sessionId(result.sessionId())
                .status(result.status())
                .suggestedStructure(toSuggestedStructureJson(result.suggestedStructure()))
                .patternSuggestions(result.patternSuggestions().stream()
                        .map(this::toPatternSuggestionJson)
                        .toList())
                .bankCategorySuggestions(result.bankCategorySuggestions().stream()
                        .map(this::toBankCategorySuggestionJson)
                        .toList())
                .autoCategorizableSuggestions(result.autoCategorizableSuggestions().stream()
                        .map(this::toAutoCategorizableSuggestionJson)
                        .toList())
                .contextMappings(result.contextMappings().stream()
                        .map(this::toContextMappingJson)
                        .toList())
                .bankCategoryFallbacks(result.bankCategoryFallbacks().stream()
                        .map(this::toBankCategoryFallbackJson)
                        .toList())
                .stats(toCategorizationStatsJson(result.stats()))
                .cost(ClassificationDto.AiCostJson.builder()
                        .tokensUsed(result.cost().tokensUsed())
                        .estimatedCost(result.cost().estimatedCost())
                        .build())
                .build();
    }
    private ClassificationDto.AiAutoCategorizableSuggestionJson toAutoCategorizableSuggestionJson(
            AiCategorizationResult.AutoCategorizableSuggestion suggestion) {
        return ClassificationDto.AiAutoCategorizableSuggestionJson.builder()
                .classification(suggestion.classification())
                .suggestedCategory(suggestion.suggestedCategory())
                .parentCategory(suggestion.parentCategory())
                .type(suggestion.type())
                .transactionCount(suggestion.transactionCount())
                .totalAmount(suggestion.totalAmount().doubleValue())
                .sampleTransactions(suggestion.sampleTransactions())
                .build();
    }
    private ClassificationDto.AiBankCategorySuggestionJson toBankCategorySuggestionJson(
            AiCategorizationResult.BankCategorySuggestion suggestion) {
        return ClassificationDto.AiBankCategorySuggestionJson.builder()
                .bankCategory(suggestion.bankCategory())
                .targetCategory(suggestion.targetCategory())
                .parentCategory(suggestion.parentCategory())
                .type(suggestion.type())
                .confidence(suggestion.confidence())
                .transactionCount(suggestion.transactionCount())
                .totalAmount(suggestion.totalAmount().doubleValue())
                .reason(suggestion.reason())
                .build();
    }
    private ClassificationDto.AiContextMappingJson toContextMappingJson(
            AiCategorizationResult.ContextMapping mapping) {
        return ClassificationDto.AiContextMappingJson.builder()
                .pattern(mapping.pattern())
                .bankCategory(mapping.bankCategory())
                .suggestedCategory(mapping.suggestedCategory())
                .parentCategory(mapping.parentCategory())
                .type(mapping.type())
                .confidence(mapping.confidence())
                .dominantSignal(mapping.dominantSignal())
                .isExistingCategory(mapping.isExistingCategory())
                .reason(mapping.reason())
                .transactionCount(mapping.transactionCount())
                .totalAmount(mapping.totalAmount().doubleValue())
                .build();
    }
    private ClassificationDto.AiBankCategoryFallbackJson toBankCategoryFallbackJson(
            AiCategorizationResult.BankCategoryFallback fallback) {
        return ClassificationDto.AiBankCategoryFallbackJson.builder()
                .bankCategory(fallback.bankCategory())
                .defaultTarget(fallback.defaultTarget())
                .parentCategory(fallback.parentCategory())
                .type(fallback.type())
                .confidence(fallback.confidence())
                .reason(fallback.reason())
                .build();
    }
    private ClassificationDto.AiSuggestedStructureJson toSuggestedStructureJson(
            AiCategorizationResult.SuggestedStructure structure) {
        return ClassificationDto.AiSuggestedStructureJson.builder()
                .outflow(structure.outflow().stream()
                        .map(this::toCategoryNodeJson)
                        .toList())
                .inflow(structure.inflow().stream()
                        .map(this::toCategoryNodeJson)
                        .toList())
                .build();
    }
    private ClassificationDto.AiCategoryNodeJson toCategoryNodeJson(
            AiCategorizationResult.CategoryNode node) {
        return ClassificationDto.AiCategoryNodeJson.builder()
                .name(node.name())
                .subCategories(node.subCategories())
                .transactionCount(node.transactionCount())
                .totalAmount(node.totalAmount().doubleValue())
                .build();
    }
    private ClassificationDto.AiPatternSuggestionJson toPatternSuggestionJson(
            AiCategorizationResult.PatternSuggestion suggestion) {
        return ClassificationDto.AiPatternSuggestionJson.builder()
                .pattern(suggestion.pattern())
                .sampleTransaction(suggestion.sampleTransaction())
                .suggestedCategory(suggestion.suggestedCategory())
                .parentCategory(suggestion.parentCategory())
                .type(suggestion.type())
                .confidence(suggestion.confidence())
                .source(suggestion.source().name())
                .transactionCount(suggestion.transactionCount())
                .totalAmount(suggestion.totalAmount().doubleValue())
                .needsUserInput(suggestion.needsUserInput())
                .build();
    }
    private ClassificationDto.AiCategorizationStatsJson toCategorizationStatsJson(
            AiCategorizationResult.CategorizationStats stats) {
        return ClassificationDto.AiCategorizationStatsJson.builder()
                .totalPatterns(stats.totalPatterns())
                .autoAccepted(stats.autoAccepted())
                .suggested(stats.suggested())
                .needsManual(stats.needsManual())
                .fromGlobalCache(stats.fromGlobalCache())
                .fromUserCache(stats.fromUserCache())
                .fromAi(stats.fromAi())
                .build();
    }
    private List<AcceptAiSuggestionsCommand.CategoryToCreate> toCategoriesToCreate(
            List<ClassificationDto.AiCategoryToCreateJson> categories) {
        if (categories == null) return List.of();
        return categories.stream()
                .map(c -> new AcceptAiSuggestionsCommand.CategoryToCreate(
                        c.getName(),
                        c.getParentName(),
                        c.getType()
                ))
                .toList();
    }
    private List<AcceptAiSuggestionsCommand.MappingToApply> toMappingsToApply(
            List<ClassificationDto.AiMappingToApplyJson> mappings) {
        if (mappings == null) return List.of();
        return mappings.stream()
                .map(m -> new AcceptAiSuggestionsCommand.MappingToApply(
                        m.getPattern(),
                        m.getBankCategory(),
                        m.getTargetCategory(),
                        m.getParentCategory(),  // Store as intendedParentCategory hint for future imports
                        m.getType(),
                        m.getConfidence()
                ))
                .toList();
    }
    private List<AcceptAiSuggestionsCommand.BankCategoryMappingToApply> toBankCategoryMappingsToApply(
            List<ClassificationDto.AiBankCategoryMappingToApplyJson> mappings) {
        if (mappings == null) return List.of();
        return mappings.stream()
                .map(m -> new AcceptAiSuggestionsCommand.BankCategoryMappingToApply(
                        m.getBankCategory(),
                        m.getTargetCategory(),
                        m.getParentCategory(),
                        m.getType(),
                        m.getConfidence()
                ))
                .toList();
    }
    private ClassificationDto.AcceptAiSuggestionsResponse toAcceptAiSuggestionsResponse(
            AcceptAiSuggestionsResult result) {
        return ClassificationDto.AcceptAiSuggestionsResponse.builder()
                .cashFlowId(result.cashFlowId().id())
                .sessionId(result.sessionId().id())
                .status(result.status())
                .categoriesCreated(result.categoriesCreated())
                .mappingsApplied(result.mappingsApplied())
                .patternsCached(result.patternsCached())
                .warnings(result.warnings())
                .validationSummary(ClassificationDto.AiStagingValidationSummaryJson.builder()
                        .totalTransactions(result.validationSummary().totalTransactions())
                        .validTransactions(result.validationSummary().validTransactions())
                        .invalidTransactions(result.validationSummary().invalidTransactions())
                        .duplicateTransactions(result.validationSummary().duplicateTransactions())
                        .readyForImport(result.validationSummary().readyForImport())
                        .build())
                .build();
    }

    private String getCurrentUserId() {
        return authenticatedUserProvider.getCurrentUserId().getId();
    }
}
