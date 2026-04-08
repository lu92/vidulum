package com.multi.vidulum.bank_data_ingestion.app.categorization;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multi.vidulum.bank_data_ingestion.domain.AiCategorizationResult;
import com.multi.vidulum.cashflow.domain.Type;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses AI response JSON into domain objects.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiCategorizationResponseParser {

    private final ObjectMapper objectMapper;

    /**
     * Parses AI response into structured result.
     *
     * @param aiResponse   the raw AI response text
     * @param patternGroups the original pattern groups (for transaction counts/amounts)
     * @return parsed result or null if parsing fails
     */
    public ParseResult parse(String aiResponse, List<PatternDeduplicator.PatternGroup> patternGroups) {
        if (aiResponse == null || aiResponse.isBlank()) {
            return ParseResult.error("Empty AI response");
        }

        try {
            // Clean response (remove markdown code blocks if present)
            String json = cleanJsonResponse(aiResponse);

            // Parse JSON
            AiResponseDto dto = objectMapper.readValue(json, AiResponseDto.class);

            if (dto == null) {
                return ParseResult.error("Failed to parse AI response JSON");
            }

            // Convert to domain objects
            List<AiCategorizationResult.PatternSuggestion> suggestions =
                    convertMappings(dto.patternMappings, patternGroups);
            List<AiCategorizationResult.BankCategorySuggestion> bankCategorySuggestions =
                    convertBankCategoryMappings(dto.bankCategoryMappings, patternGroups);
            List<AiCategorizationResult.UnrecognizedPattern> unrecognized =
                    convertUnrecognizedPatterns(dto.unrecognizedPatterns, patternGroups);

            // Convert structure and apply post-processing
            AiCategorizationResult.SuggestedStructure rawStructure = convertStructure(dto.categoryStructure);

            // Post-processing step 1: Enrich with transaction counts and filter empty categories
            AiCategorizationResult.SuggestedStructure enrichedStructure =
                    enrichAndFilterCategories(rawStructure, suggestions, bankCategorySuggestions);

            // Post-processing step 2: Flatten single-child hierarchies (after filtering, so we catch cases
            // where filtering leaves only 1 child)
            AiCategorizationResult.SuggestedStructure finalStructure = flattenSingleChildCategories(enrichedStructure);

            return ParseResult.success(finalStructure, suggestions, bankCategorySuggestions, unrecognized);

        } catch (Exception e) {
            log.error("Failed to parse AI categorization response: {}", e.getMessage(), e);
            return ParseResult.error("JSON parse error: " + e.getMessage());
        }
    }

    /**
     * Removes markdown code blocks and extra whitespace from AI response.
     */
    private String cleanJsonResponse(String response) {
        String cleaned = response.trim();

        // Remove markdown code blocks
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }

        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }

        return cleaned.trim();
    }

    private AiCategorizationResult.SuggestedStructure convertStructure(CategoryStructureDto dto) {
        if (dto == null) {
            return AiCategorizationResult.SuggestedStructure.empty();
        }

        List<AiCategorizationResult.CategoryNode> outflow = new ArrayList<>();
        List<AiCategorizationResult.CategoryNode> inflow = new ArrayList<>();

        if (dto.outflow != null) {
            for (CategoryNodeDto node : dto.outflow) {
                outflow.add(new AiCategorizationResult.CategoryNode(
                        node.name,
                        node.subCategories != null ? node.subCategories : List.of()
                ));
            }
        }

        if (dto.inflow != null) {
            for (CategoryNodeDto node : dto.inflow) {
                inflow.add(new AiCategorizationResult.CategoryNode(
                        node.name,
                        node.subCategories != null ? node.subCategories : List.of()
                ));
            }
        }

        return new AiCategorizationResult.SuggestedStructure(outflow, inflow);
    }

    /**
     * Flattens single-child hierarchies.
     * If a parent has only 1 subcategory, the subcategory is promoted to root level.
     * Example: "Żywność" → ["Sklepy spożywcze"] becomes just "Sklepy spożywcze" (no parent)
     */
    AiCategorizationResult.SuggestedStructure flattenSingleChildCategories(
            AiCategorizationResult.SuggestedStructure structure) {

        List<AiCategorizationResult.CategoryNode> flattenedOutflow = flattenNodes(structure.outflow());
        List<AiCategorizationResult.CategoryNode> flattenedInflow = flattenNodes(structure.inflow());

        return new AiCategorizationResult.SuggestedStructure(flattenedOutflow, flattenedInflow);
    }

    private List<AiCategorizationResult.CategoryNode> flattenNodes(List<AiCategorizationResult.CategoryNode> nodes) {
        List<AiCategorizationResult.CategoryNode> result = new ArrayList<>();

        for (AiCategorizationResult.CategoryNode node : nodes) {
            if (node.subCategories() != null && node.subCategories().size() == 1) {
                // Single child - promote to root level without parent
                String childName = node.subCategories().get(0);
                result.add(new AiCategorizationResult.CategoryNode(
                        childName,
                        List.of(),  // No subcategories - it's now a root category
                        node.transactionCount(),
                        node.totalAmount()
                ));
                log.debug("Flattened single-child hierarchy: {} → {} became just {}",
                        node.name(), childName, childName);
            } else {
                // Keep as is (0 children or 2+ children)
                result.add(node);
            }
        }

        return result;
    }

    /**
     * Enriches category structure with transaction counts from pattern mappings,
     * and filters out categories that have no transactions.
     */
    AiCategorizationResult.SuggestedStructure enrichAndFilterCategories(
            AiCategorizationResult.SuggestedStructure structure,
            List<AiCategorizationResult.PatternSuggestion> patternSuggestions,
            List<AiCategorizationResult.BankCategorySuggestion> bankCategorySuggestions) {

        // Build maps: category name → (transactionCount, totalAmount)
        Map<String, Integer> categoryTransactionCounts = new java.util.HashMap<>();
        Map<String, BigDecimal> categoryAmounts = new java.util.HashMap<>();

        // Count from pattern suggestions
        for (AiCategorizationResult.PatternSuggestion ps : patternSuggestions) {
            String category = ps.suggestedCategory();
            String parent = ps.parentCategory();

            // Add to subcategory
            categoryTransactionCounts.merge(category, ps.transactionCount(), Integer::sum);
            categoryAmounts.merge(category, ps.totalAmount(), BigDecimal::add);

            // Also add to parent if exists
            if (parent != null && !parent.isBlank()) {
                categoryTransactionCounts.merge(parent, ps.transactionCount(), Integer::sum);
                categoryAmounts.merge(parent, ps.totalAmount(), BigDecimal::add);
            }
        }

        // Count from bank category suggestions
        for (AiCategorizationResult.BankCategorySuggestion bcs : bankCategorySuggestions) {
            String category = bcs.targetCategory();
            String parent = bcs.parentCategory();

            categoryTransactionCounts.merge(category, bcs.transactionCount(), Integer::sum);
            categoryAmounts.merge(category, bcs.totalAmount(), BigDecimal::add);

            if (parent != null && !parent.isBlank()) {
                categoryTransactionCounts.merge(parent, bcs.transactionCount(), Integer::sum);
                categoryAmounts.merge(parent, bcs.totalAmount(), BigDecimal::add);
            }
        }

        // Enrich and filter outflow
        List<AiCategorizationResult.CategoryNode> enrichedOutflow =
                enrichAndFilterNodes(structure.outflow(), categoryTransactionCounts, categoryAmounts);

        // Enrich and filter inflow
        List<AiCategorizationResult.CategoryNode> enrichedInflow =
                enrichAndFilterNodes(structure.inflow(), categoryTransactionCounts, categoryAmounts);

        return new AiCategorizationResult.SuggestedStructure(enrichedOutflow, enrichedInflow);
    }

    private List<AiCategorizationResult.CategoryNode> enrichAndFilterNodes(
            List<AiCategorizationResult.CategoryNode> nodes,
            Map<String, Integer> transactionCounts,
            Map<String, BigDecimal> amounts) {

        List<AiCategorizationResult.CategoryNode> result = new ArrayList<>();

        for (AiCategorizationResult.CategoryNode node : nodes) {
            int nodeTransactionCount = transactionCounts.getOrDefault(node.name(), 0);
            BigDecimal nodeAmount = amounts.getOrDefault(node.name(), BigDecimal.ZERO);

            // Filter subcategories that have transactions
            List<String> filteredSubs = new ArrayList<>();
            for (String sub : node.subCategories()) {
                int subCount = transactionCounts.getOrDefault(sub, 0);
                if (subCount > 0) {
                    filteredSubs.add(sub);
                } else {
                    log.debug("Filtering out empty subcategory: {} (0 transactions)", sub);
                }
            }

            // Calculate parent transaction count as sum of children if has subcategories
            if (!filteredSubs.isEmpty()) {
                int childTotal = filteredSubs.stream()
                        .mapToInt(sub -> transactionCounts.getOrDefault(sub, 0))
                        .sum();
                nodeTransactionCount = Math.max(nodeTransactionCount, childTotal);
            }

            // Only include if has transactions OR has valid subcategories
            if (nodeTransactionCount > 0 || !filteredSubs.isEmpty()) {
                result.add(new AiCategorizationResult.CategoryNode(
                        node.name(),
                        filteredSubs,
                        nodeTransactionCount,
                        nodeAmount
                ));
            } else {
                log.debug("Filtering out empty parent category: {} (0 transactions, 0 subcategories)",
                        node.name());
            }
        }

        return result;
    }

    private List<AiCategorizationResult.PatternSuggestion> convertMappings(
            List<PatternMappingDto> mappings,
            List<PatternDeduplicator.PatternGroup> patternGroups) {

        if (mappings == null || mappings.isEmpty()) {
            return List.of();
        }

        // Create lookup map for pattern groups
        Map<String, PatternDeduplicator.PatternGroup> groupMap = patternGroups.stream()
                .collect(java.util.stream.Collectors.toMap(
                        PatternDeduplicator.PatternGroup::pattern,
                        g -> g,
                        (a, b) -> a // keep first if duplicate
                ));

        List<AiCategorizationResult.PatternSuggestion> suggestions = new ArrayList<>();

        for (PatternMappingDto mapping : mappings) {
            PatternDeduplicator.PatternGroup group = groupMap.get(mapping.pattern);

            int transactionCount = group != null ? group.transactionCount() : 1;
            BigDecimal totalAmount = group != null ? group.totalAmount() : BigDecimal.ZERO;
            String sampleTransaction = group != null ? group.sampleTransaction() : mapping.pattern;

            Type type;
            try {
                type = Type.valueOf(mapping.type);
            } catch (Exception e) {
                type = Type.OUTFLOW; // default
            }

            suggestions.add(AiCategorizationResult.PatternSuggestion.fromAi(
                    mapping.pattern,
                    sampleTransaction,
                    mapping.suggestedCategory,
                    mapping.parentCategory,
                    type,
                    mapping.confidence,
                    transactionCount,
                    totalAmount,
                    mapping.isExistingCategory != null ? mapping.isExistingCategory : false,
                    mapping.reason
            ));
        }

        return suggestions;
    }

    /**
     * Converts unrecognized patterns from AI response.
     */
    private List<AiCategorizationResult.UnrecognizedPattern> convertUnrecognizedPatterns(
            List<UnrecognizedPatternDto> unrecognized,
            List<PatternDeduplicator.PatternGroup> patternGroups) {

        if (unrecognized == null || unrecognized.isEmpty()) {
            return List.of();
        }

        // Create lookup map for pattern groups
        Map<String, PatternDeduplicator.PatternGroup> groupMap = patternGroups.stream()
                .collect(java.util.stream.Collectors.toMap(
                        PatternDeduplicator.PatternGroup::pattern,
                        g -> g,
                        (a, b) -> a
                ));

        List<AiCategorizationResult.UnrecognizedPattern> result = new ArrayList<>();

        for (UnrecognizedPatternDto dto : unrecognized) {
            PatternDeduplicator.PatternGroup group = groupMap.get(dto.pattern);

            int transactionCount = group != null ? group.transactionCount() : 1;
            BigDecimal totalAmount = group != null ? group.totalAmount() : BigDecimal.ZERO;

            Type type;
            try {
                type = Type.valueOf(dto.type);
            } catch (Exception e) {
                type = Type.OUTFLOW;
            }

            result.add(new AiCategorizationResult.UnrecognizedPattern(
                    dto.pattern,
                    type,
                    dto.reason,
                    transactionCount,
                    totalAmount
            ));
        }

        return result;
    }

    /**
     * Converts bank category mappings from AI response.
     */
    private List<AiCategorizationResult.BankCategorySuggestion> convertBankCategoryMappings(
            List<BankCategoryMappingDto> mappings,
            List<PatternDeduplicator.PatternGroup> patternGroups) {

        if (mappings == null || mappings.isEmpty()) {
            return List.of();
        }

        // Create lookup map by bankCategory to get transaction counts
        Map<String, Integer> bankCategoryTransactionCounts = new java.util.HashMap<>();
        Map<String, BigDecimal> bankCategoryAmounts = new java.util.HashMap<>();

        for (PatternDeduplicator.PatternGroup group : patternGroups) {
            String bankCategory = group.bankCategory();
            if (bankCategory != null && !bankCategory.isBlank()) {
                bankCategoryTransactionCounts.merge(bankCategory, group.transactionCount(), Integer::sum);
                bankCategoryAmounts.merge(bankCategory, group.totalAmount(), BigDecimal::add);
            }
        }

        List<AiCategorizationResult.BankCategorySuggestion> result = new ArrayList<>();

        for (BankCategoryMappingDto dto : mappings) {
            Type type;
            try {
                type = Type.valueOf(dto.type);
            } catch (Exception e) {
                type = Type.OUTFLOW;
            }

            int transactionCount = bankCategoryTransactionCounts.getOrDefault(dto.bankCategory, 1);
            BigDecimal totalAmount = bankCategoryAmounts.getOrDefault(dto.bankCategory, BigDecimal.ZERO);

            result.add(new AiCategorizationResult.BankCategorySuggestion(
                    dto.bankCategory,
                    dto.targetCategory,
                    dto.parentCategory,
                    type,
                    dto.confidence,
                    transactionCount,
                    totalAmount,
                    dto.reason
            ));
        }

        return result;
    }

    // ============ DTOs for JSON parsing ============

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AiResponseDto {
        private CategoryStructureDto categoryStructure;
        private List<PatternMappingDto> patternMappings;
        private List<BankCategoryMappingDto> bankCategoryMappings;
        private List<UnrecognizedPatternDto> unrecognizedPatterns;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CategoryStructureDto {
        private List<CategoryNodeDto> outflow;
        private List<CategoryNodeDto> inflow;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CategoryNodeDto {
        private String name;
        private List<String> subCategories;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PatternMappingDto {
        private String pattern;
        private String suggestedCategory;
        private String parentCategory;
        private String type;
        private int confidence;
        private Boolean isExistingCategory;
        private String reason;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UnrecognizedPatternDto {
        private String pattern;
        private String type;
        private String reason;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BankCategoryMappingDto {
        private String bankCategory;
        private String targetCategory;
        private String parentCategory;
        private String type;
        private int confidence;
        private String reason;
    }

    // ============ Result wrapper ============

    public record ParseResult(
            boolean success,
            AiCategorizationResult.SuggestedStructure structure,
            List<AiCategorizationResult.PatternSuggestion> suggestions,
            List<AiCategorizationResult.BankCategorySuggestion> bankCategorySuggestions,
            List<AiCategorizationResult.UnrecognizedPattern> unrecognizedPatterns,
            String errorMessage
    ) {
        public static ParseResult success(
                AiCategorizationResult.SuggestedStructure structure,
                List<AiCategorizationResult.PatternSuggestion> suggestions,
                List<AiCategorizationResult.BankCategorySuggestion> bankCategorySuggestions,
                List<AiCategorizationResult.UnrecognizedPattern> unrecognizedPatterns) {
            return new ParseResult(true, structure, suggestions, bankCategorySuggestions, unrecognizedPatterns, null);
        }

        public static ParseResult error(String message) {
            return new ParseResult(
                    false,
                    AiCategorizationResult.SuggestedStructure.empty(),
                    List.of(),
                    List.of(),
                    List.of(),
                    message
            );
        }
    }
}
