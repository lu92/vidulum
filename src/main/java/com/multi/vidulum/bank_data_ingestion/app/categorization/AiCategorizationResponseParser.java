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
import java.util.Set;
import java.util.stream.Collectors;

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
            List<AiCategorizationResult.BankCategorySuggestion> rawBankCategorySuggestions =
                    convertBankCategoryMappings(dto.bankCategoryMappings, patternGroups);
            // Validate bankCategoryMappings - filter out invalid ones where AI confused merchant names with bankCategories
            List<AiCategorizationResult.BankCategorySuggestion> bankCategorySuggestions =
                    validateBankCategoryMappings(rawBankCategorySuggestions, patternGroups);
            List<AiCategorizationResult.UnrecognizedPattern> unrecognized =
                    convertUnrecognizedPatterns(dto.unrecognizedPatterns, patternGroups);
            List<AiCategorizationResult.StructureOptimization> structureOptimizations =
                    convertStructureOptimizations(dto.structureOptimizations);

            // Convert structure and apply post-processing
            AiCategorizationResult.SuggestedStructure rawStructure = convertStructure(dto.categoryStructure);

            // Post-processing step 0: Fix missing categories in structure
            // AI often generates patternMappings referencing categories not in categoryStructure
            AiCategorizationResult.SuggestedStructure fixedStructure =
                    fixMissingCategoriesInStructure(rawStructure, suggestions, bankCategorySuggestions);

            // Post-processing step 1: Enrich with transaction counts and filter empty categories
            AiCategorizationResult.SuggestedStructure enrichedStructure =
                    enrichAndFilterCategories(fixedStructure, suggestions, bankCategorySuggestions);

            // Post-processing step 2: Flatten single-child hierarchies (after filtering, so we catch cases
            // where filtering leaves only 1 child)
            AiCategorizationResult.SuggestedStructure finalStructure = flattenSingleChildCategories(enrichedStructure);

            return ParseResult.success(finalStructure, suggestions, bankCategorySuggestions, unrecognized, structureOptimizations);

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
     * Fixes missing categories in structure by adding categories referenced in patternMappings
     * but not present in categoryStructure.
     *
     * This handles a common AI bug where it generates patternMappings with parentCategory values
     * like "Żywność", "Transport" that are NOT actually included in categoryStructure.
     *
     * The fix:
     * 1. Collect all parentCategory + suggestedCategory from mappings
     * 2. Check which ones are missing from structure
     * 3. Add missing parents as new nodes, with suggestedCategory as subcategory
     */
    AiCategorizationResult.SuggestedStructure fixMissingCategoriesInStructure(
            AiCategorizationResult.SuggestedStructure structure,
            List<AiCategorizationResult.PatternSuggestion> patternSuggestions,
            List<AiCategorizationResult.BankCategorySuggestion> bankCategorySuggestions) {

        // Build sets of existing category names by type
        Set<String> existingOutflowCategories = collectAllCategoryNames(structure.outflow());
        Set<String> existingInflowCategories = collectAllCategoryNames(structure.inflow());

        // Collect missing parents and their children from pattern mappings
        // Map: parentCategory -> Set of subcategories
        Map<String, Set<String>> missingOutflowParents = new java.util.HashMap<>();
        Map<String, Set<String>> missingInflowParents = new java.util.HashMap<>();

        // Also track orphan categories (no parent but not in structure)
        Set<String> orphanOutflowCategories = new java.util.HashSet<>();
        Set<String> orphanInflowCategories = new java.util.HashSet<>();

        // Process pattern suggestions
        for (AiCategorizationResult.PatternSuggestion ps : patternSuggestions) {
            String parent = ps.parentCategory();
            String suggested = ps.suggestedCategory();
            boolean isOutflow = ps.type() == Type.OUTFLOW;

            Set<String> existingSet = isOutflow ? existingOutflowCategories : existingInflowCategories;
            Map<String, Set<String>> missingParentsMap = isOutflow ? missingOutflowParents : missingInflowParents;
            Set<String> orphansSet = isOutflow ? orphanOutflowCategories : orphanInflowCategories;

            if (parent != null && !parent.isBlank()) {
                // Has parent - check if parent exists
                if (!existingSet.contains(parent)) {
                    missingParentsMap.computeIfAbsent(parent, k -> new java.util.HashSet<>()).add(suggested);
                    log.debug("Found missing parent category: {} (for subcategory: {}, type: {})",
                            parent, suggested, ps.type());
                }
            } else {
                // No parent - check if suggested exists as root
                if (!existingSet.contains(suggested)) {
                    orphansSet.add(suggested);
                    log.debug("Found orphan category (no parent, not in structure): {} (type: {})",
                            suggested, ps.type());
                }
            }
        }

        // Process bank category suggestions similarly
        for (AiCategorizationResult.BankCategorySuggestion bcs : bankCategorySuggestions) {
            String parent = bcs.parentCategory();
            String target = bcs.targetCategory();
            boolean isOutflow = bcs.type() == Type.OUTFLOW;

            Set<String> existingSet = isOutflow ? existingOutflowCategories : existingInflowCategories;
            Map<String, Set<String>> missingParentsMap = isOutflow ? missingOutflowParents : missingInflowParents;
            Set<String> orphansSet = isOutflow ? orphanOutflowCategories : orphanInflowCategories;

            if (parent != null && !parent.isBlank() && !existingSet.contains(parent)) {
                missingParentsMap.computeIfAbsent(parent, k -> new java.util.HashSet<>()).add(target);
            } else if ((parent == null || parent.isBlank()) && !existingSet.contains(target)) {
                orphansSet.add(target);
            }
        }

        // Build fixed structure
        List<AiCategorizationResult.CategoryNode> fixedOutflow = new ArrayList<>(structure.outflow());
        List<AiCategorizationResult.CategoryNode> fixedInflow = new ArrayList<>(structure.inflow());

        // Add missing parent categories
        for (Map.Entry<String, Set<String>> entry : missingOutflowParents.entrySet()) {
            String parentName = entry.getKey();
            List<String> subcategories = new ArrayList<>(entry.getValue());
            fixedOutflow.add(new AiCategorizationResult.CategoryNode(parentName, subcategories));
            log.info("Auto-added missing OUTFLOW parent category: {} with subcategories: {}", parentName, subcategories);
        }

        for (Map.Entry<String, Set<String>> entry : missingInflowParents.entrySet()) {
            String parentName = entry.getKey();
            List<String> subcategories = new ArrayList<>(entry.getValue());
            fixedInflow.add(new AiCategorizationResult.CategoryNode(parentName, subcategories));
            log.info("Auto-added missing INFLOW parent category: {} with subcategories: {}", parentName, subcategories);
        }

        // Add orphan categories as root-level (no subcategories)
        for (String orphan : orphanOutflowCategories) {
            fixedOutflow.add(new AiCategorizationResult.CategoryNode(orphan, List.of()));
            log.info("Auto-added orphan OUTFLOW category as root: {}", orphan);
        }

        for (String orphan : orphanInflowCategories) {
            fixedInflow.add(new AiCategorizationResult.CategoryNode(orphan, List.of()));
            log.info("Auto-added orphan INFLOW category as root: {}", orphan);
        }

        int totalAdded = missingOutflowParents.size() + missingInflowParents.size()
                + orphanOutflowCategories.size() + orphanInflowCategories.size();
        if (totalAdded > 0) {
            log.warn("Fixed {} missing categories in AI response categoryStructure. " +
                    "This indicates AI didn't follow the consistency rule.", totalAdded);
        }

        return new AiCategorizationResult.SuggestedStructure(fixedOutflow, fixedInflow);
    }

    /**
     * Collects all category names from nodes (both parent names and subcategory names).
     */
    private Set<String> collectAllCategoryNames(List<AiCategorizationResult.CategoryNode> nodes) {
        Set<String> names = new java.util.HashSet<>();
        for (AiCategorizationResult.CategoryNode node : nodes) {
            names.add(node.name());
            names.addAll(node.subCategories());
        }
        return names;
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

    /**
     * Validates that bankCategoryMappings reference actual bankCategories from transactions.
     * Removes mappings that reference non-existent bankCategories (e.g., merchant names like "ZABKA").
     *
     * This prevents a common AI mistake where it confuses merchant names (patterns) with
     * bank category values. For example, AI might incorrectly return:
     *   {"bankCategory": "ZABKA", "targetCategory": "Zakupy"}
     * when the actual bankCategory in the data is "TRANSAKCJA KARTĄ PŁATNICZĄ".
     *
     * @param mappings the mappings from AI
     * @param patternGroups the original pattern groups with actual bankCategories
     * @return filtered list containing only valid mappings
     */
    private List<AiCategorizationResult.BankCategorySuggestion> validateBankCategoryMappings(
            List<AiCategorizationResult.BankCategorySuggestion> mappings,
            List<PatternDeduplicator.PatternGroup> patternGroups) {

        if (mappings.isEmpty()) {
            return mappings;
        }

        // Collect actual bankCategories from transactions
        Set<String> actualBankCategories = patternGroups.stream()
                .map(PatternDeduplicator.PatternGroup::bankCategory)
                .filter(bc -> bc != null && !bc.isBlank())
                .map(String::toUpperCase)
                .collect(Collectors.toSet());

        // Filter out mappings that don't match actual bankCategories
        List<AiCategorizationResult.BankCategorySuggestion> validMappings = mappings.stream()
                .filter(m -> {
                    boolean isValid = actualBankCategories.contains(m.bankCategory().toUpperCase());
                    if (!isValid) {
                        log.warn("Removing invalid bankCategoryMapping: '{}' is not an actual bankCategory. " +
                                        "This appears to be a merchant name, not a bank category. Actual bankCategories: {}",
                                m.bankCategory(), actualBankCategories);
                    }
                    return isValid;
                })
                .toList();

        if (validMappings.size() < mappings.size()) {
            log.info("Filtered out {} invalid bankCategoryMappings (AI confused merchant names with bankCategories)",
                    mappings.size() - validMappings.size());
        }

        return validMappings;
    }

    /**
     * Converts structure optimizations from AI response.
     * These are suggestions for reorganizing existing categories based on cached intents.
     */
    private List<AiCategorizationResult.StructureOptimization> convertStructureOptimizations(
            List<StructureOptimizationDto> optimizations) {

        if (optimizations == null || optimizations.isEmpty()) {
            return List.of();
        }

        List<AiCategorizationResult.StructureOptimization> result = new ArrayList<>();

        for (StructureOptimizationDto dto : optimizations) {
            Type type;
            try {
                type = Type.valueOf(dto.type);
            } catch (Exception e) {
                type = Type.OUTFLOW;
            }

            result.add(new AiCategorizationResult.StructureOptimization(
                    dto.categoryName,
                    dto.suggestedParent,
                    dto.currentParent,
                    type,
                    dto.affectedTransactionCount,
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
        private List<StructureOptimizationDto> structureOptimizations;
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

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StructureOptimizationDto {
        private String categoryName;
        private String suggestedParent;
        private String currentParent;
        private String type;
        private int affectedTransactionCount;
        private String reason;
    }

    // ============ Result wrapper ============

    public record ParseResult(
            boolean success,
            AiCategorizationResult.SuggestedStructure structure,
            List<AiCategorizationResult.PatternSuggestion> suggestions,
            List<AiCategorizationResult.BankCategorySuggestion> bankCategorySuggestions,
            List<AiCategorizationResult.UnrecognizedPattern> unrecognizedPatterns,
            List<AiCategorizationResult.StructureOptimization> structureOptimizations,
            String errorMessage
    ) {
        public static ParseResult success(
                AiCategorizationResult.SuggestedStructure structure,
                List<AiCategorizationResult.PatternSuggestion> suggestions,
                List<AiCategorizationResult.BankCategorySuggestion> bankCategorySuggestions,
                List<AiCategorizationResult.UnrecognizedPattern> unrecognizedPatterns,
                List<AiCategorizationResult.StructureOptimization> structureOptimizations) {
            return new ParseResult(true, structure, suggestions, bankCategorySuggestions, unrecognizedPatterns, structureOptimizations, null);
        }

        public static ParseResult error(String message) {
            return new ParseResult(
                    false,
                    AiCategorizationResult.SuggestedStructure.empty(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    message
            );
        }
    }
}
