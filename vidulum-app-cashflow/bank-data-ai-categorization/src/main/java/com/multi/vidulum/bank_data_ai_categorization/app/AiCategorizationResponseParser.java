package com.multi.vidulum.bank_data_ai_categorization.app;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multi.vidulum.bank_data_ai_categorization.api.CategorizationSuggestions;
import com.multi.vidulum.cashflow.api.Type;
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
            List<CategorizationSuggestions.PatternSuggestion> suggestions =
                    convertMappings(dto.patternMappings, patternGroups);
            List<CategorizationSuggestions.BankCategorySuggestion> rawBankCategorySuggestions =
                    convertBankCategoryMappings(dto.bankCategoryMappings, patternGroups);
            // Validate bankCategoryMappings - filter out invalid ones where AI confused merchant names with bankCategories
            List<CategorizationSuggestions.BankCategorySuggestion> bankCategorySuggestions =
                    validateBankCategoryMappings(rawBankCategorySuggestions, patternGroups);
            List<CategorizationSuggestions.UnrecognizedPattern> unrecognized =
                    convertUnrecognizedPatterns(dto.unrecognizedPatterns, patternGroups);
            List<CategorizationSuggestions.StructureOptimization> structureOptimizations =
                    convertStructureOptimizations(dto.structureOptimizations);

            // Convert structure and apply post-processing
            CategorizationSuggestions.SuggestedStructure rawStructure = convertStructure(dto.categoryStructure);

            // Post-processing step 0: Fix missing categories in structure
            // AI often generates patternMappings referencing categories not in categoryStructure
            CategorizationSuggestions.SuggestedStructure fixedStructure =
                    fixMissingCategoriesInStructure(rawStructure, suggestions, bankCategorySuggestions);

            // Post-processing step 1: Enrich with transaction counts and filter empty categories
            CategorizationSuggestions.SuggestedStructure enrichedStructure =
                    enrichAndFilterCategories(fixedStructure, suggestions, bankCategorySuggestions);

            // Post-processing step 2: Flatten single-child hierarchies (after filtering, so we catch cases
            // where filtering leaves only 1 child)
            CategorizationSuggestions.SuggestedStructure finalStructure = flattenSingleChildCategories(enrichedStructure);

            // Convert context mappings and bank category fallbacks (new three-signal format)
            List<CategorizationSuggestions.ContextMapping> contextMappings =
                    convertContextMappings(dto.contextMappings, patternGroups);
            List<CategorizationSuggestions.BankCategoryFallback> bankCategoryFallbacks =
                    convertBankCategoryFallbacks(dto.bankCategoryFallbacks, patternGroups);

            return ParseResult.success(finalStructure, suggestions, bankCategorySuggestions, unrecognized, structureOptimizations, contextMappings, bankCategoryFallbacks);

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

    private CategorizationSuggestions.SuggestedStructure convertStructure(CategoryStructureDto dto) {
        if (dto == null) {
            return CategorizationSuggestions.SuggestedStructure.empty();
        }

        List<CategorizationSuggestions.CategoryNode> outflow = new ArrayList<>();
        List<CategorizationSuggestions.CategoryNode> inflow = new ArrayList<>();

        if (dto.outflow != null) {
            for (CategoryNodeDto node : dto.outflow) {
                outflow.add(new CategorizationSuggestions.CategoryNode(
                        node.name,
                        node.subCategories != null ? node.subCategories : List.of()
                ));
            }
        }

        if (dto.inflow != null) {
            for (CategoryNodeDto node : dto.inflow) {
                inflow.add(new CategorizationSuggestions.CategoryNode(
                        node.name,
                        node.subCategories != null ? node.subCategories : List.of()
                ));
            }
        }

        return new CategorizationSuggestions.SuggestedStructure(outflow, inflow);
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
    CategorizationSuggestions.SuggestedStructure fixMissingCategoriesInStructure(
            CategorizationSuggestions.SuggestedStructure structure,
            List<CategorizationSuggestions.PatternSuggestion> patternSuggestions,
            List<CategorizationSuggestions.BankCategorySuggestion> bankCategorySuggestions) {

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
        for (CategorizationSuggestions.PatternSuggestion ps : patternSuggestions) {
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
        for (CategorizationSuggestions.BankCategorySuggestion bcs : bankCategorySuggestions) {
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
        List<CategorizationSuggestions.CategoryNode> fixedOutflow = new ArrayList<>(structure.outflow());
        List<CategorizationSuggestions.CategoryNode> fixedInflow = new ArrayList<>(structure.inflow());

        // Add missing parent categories
        for (Map.Entry<String, Set<String>> entry : missingOutflowParents.entrySet()) {
            String parentName = entry.getKey();
            List<String> subcategories = new ArrayList<>(entry.getValue());
            fixedOutflow.add(new CategorizationSuggestions.CategoryNode(parentName, subcategories));
            log.info("Auto-added missing OUTFLOW parent category: {} with subcategories: {}", parentName, subcategories);
        }

        for (Map.Entry<String, Set<String>> entry : missingInflowParents.entrySet()) {
            String parentName = entry.getKey();
            List<String> subcategories = new ArrayList<>(entry.getValue());
            fixedInflow.add(new CategorizationSuggestions.CategoryNode(parentName, subcategories));
            log.info("Auto-added missing INFLOW parent category: {} with subcategories: {}", parentName, subcategories);
        }

        // Add orphan categories as root-level (no subcategories)
        for (String orphan : orphanOutflowCategories) {
            fixedOutflow.add(new CategorizationSuggestions.CategoryNode(orphan, List.of()));
            log.info("Auto-added orphan OUTFLOW category as root: {}", orphan);
        }

        for (String orphan : orphanInflowCategories) {
            fixedInflow.add(new CategorizationSuggestions.CategoryNode(orphan, List.of()));
            log.info("Auto-added orphan INFLOW category as root: {}", orphan);
        }

        int totalAdded = missingOutflowParents.size() + missingInflowParents.size()
                + orphanOutflowCategories.size() + orphanInflowCategories.size();
        if (totalAdded > 0) {
            log.warn("Fixed {} missing categories in AI response categoryStructure. " +
                    "This indicates AI didn't follow the consistency rule.", totalAdded);
        }

        return new CategorizationSuggestions.SuggestedStructure(fixedOutflow, fixedInflow);
    }

    /**
     * Collects all category names from nodes (both parent names and subcategory names).
     */
    private Set<String> collectAllCategoryNames(List<CategorizationSuggestions.CategoryNode> nodes) {
        Set<String> names = new java.util.HashSet<>();
        for (CategorizationSuggestions.CategoryNode node : nodes) {
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
    CategorizationSuggestions.SuggestedStructure flattenSingleChildCategories(
            CategorizationSuggestions.SuggestedStructure structure) {

        List<CategorizationSuggestions.CategoryNode> flattenedOutflow = flattenNodes(structure.outflow());
        List<CategorizationSuggestions.CategoryNode> flattenedInflow = flattenNodes(structure.inflow());

        return new CategorizationSuggestions.SuggestedStructure(flattenedOutflow, flattenedInflow);
    }

    private List<CategorizationSuggestions.CategoryNode> flattenNodes(List<CategorizationSuggestions.CategoryNode> nodes) {
        List<CategorizationSuggestions.CategoryNode> result = new ArrayList<>();

        for (CategorizationSuggestions.CategoryNode node : nodes) {
            if (node.subCategories() != null && node.subCategories().size() == 1) {
                // Single child - promote to root level without parent
                String childName = node.subCategories().get(0);
                result.add(new CategorizationSuggestions.CategoryNode(
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
    CategorizationSuggestions.SuggestedStructure enrichAndFilterCategories(
            CategorizationSuggestions.SuggestedStructure structure,
            List<CategorizationSuggestions.PatternSuggestion> patternSuggestions,
            List<CategorizationSuggestions.BankCategorySuggestion> bankCategorySuggestions) {

        // Build maps: category name → (transactionCount, totalAmount)
        Map<String, Integer> categoryTransactionCounts = new java.util.HashMap<>();
        Map<String, BigDecimal> categoryAmounts = new java.util.HashMap<>();

        // Count from pattern suggestions
        for (CategorizationSuggestions.PatternSuggestion ps : patternSuggestions) {
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
        for (CategorizationSuggestions.BankCategorySuggestion bcs : bankCategorySuggestions) {
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
        List<CategorizationSuggestions.CategoryNode> enrichedOutflow =
                enrichAndFilterNodes(structure.outflow(), categoryTransactionCounts, categoryAmounts);

        // Enrich and filter inflow
        List<CategorizationSuggestions.CategoryNode> enrichedInflow =
                enrichAndFilterNodes(structure.inflow(), categoryTransactionCounts, categoryAmounts);

        return new CategorizationSuggestions.SuggestedStructure(enrichedOutflow, enrichedInflow);
    }

    private List<CategorizationSuggestions.CategoryNode> enrichAndFilterNodes(
            List<CategorizationSuggestions.CategoryNode> nodes,
            Map<String, Integer> transactionCounts,
            Map<String, BigDecimal> amounts) {

        List<CategorizationSuggestions.CategoryNode> result = new ArrayList<>();

        for (CategorizationSuggestions.CategoryNode node : nodes) {
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
                result.add(new CategorizationSuggestions.CategoryNode(
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

    private List<CategorizationSuggestions.PatternSuggestion> convertMappings(
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

        List<CategorizationSuggestions.PatternSuggestion> suggestions = new ArrayList<>();

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

            suggestions.add(CategorizationSuggestions.PatternSuggestion.fromAi(
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
    private List<CategorizationSuggestions.UnrecognizedPattern> convertUnrecognizedPatterns(
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

        List<CategorizationSuggestions.UnrecognizedPattern> result = new ArrayList<>();

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

            result.add(new CategorizationSuggestions.UnrecognizedPattern(
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
    private List<CategorizationSuggestions.BankCategorySuggestion> convertBankCategoryMappings(
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

        List<CategorizationSuggestions.BankCategorySuggestion> result = new ArrayList<>();

        for (BankCategoryMappingDto dto : mappings) {
            Type type;
            try {
                type = Type.valueOf(dto.type);
            } catch (Exception e) {
                type = Type.OUTFLOW;
            }

            int transactionCount = bankCategoryTransactionCounts.getOrDefault(dto.bankCategory, 1);
            BigDecimal totalAmount = bankCategoryAmounts.getOrDefault(dto.bankCategory, BigDecimal.ZERO);

            result.add(new CategorizationSuggestions.BankCategorySuggestion(
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
    private List<CategorizationSuggestions.BankCategorySuggestion> validateBankCategoryMappings(
            List<CategorizationSuggestions.BankCategorySuggestion> mappings,
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
        List<CategorizationSuggestions.BankCategorySuggestion> validMappings = mappings.stream()
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
    private List<CategorizationSuggestions.StructureOptimization> convertStructureOptimizations(
            List<StructureOptimizationDto> optimizations) {

        if (optimizations == null || optimizations.isEmpty()) {
            return List.of();
        }

        List<CategorizationSuggestions.StructureOptimization> result = new ArrayList<>();

        for (StructureOptimizationDto dto : optimizations) {
            Type type;
            try {
                type = Type.valueOf(dto.type);
            } catch (Exception e) {
                type = Type.OUTFLOW;
            }

            result.add(new CategorizationSuggestions.StructureOptimization(
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

    // ============ Context mapping conversion (three-signal) ============

    private List<CategorizationSuggestions.ContextMapping> convertContextMappings(
            List<ContextMappingDto> dtos,
            List<PatternDeduplicator.PatternGroup> patternGroups) {
        if (dtos == null || dtos.isEmpty()) {
            return List.of();
        }

        List<CategorizationSuggestions.ContextMapping> result = new ArrayList<>();
        for (ContextMappingDto dto : dtos) {
            if (dto.suggestedCategory == null || dto.suggestedCategory.isBlank()) continue;

            Type type = "INFLOW".equalsIgnoreCase(dto.type) ? Type.INFLOW : Type.OUTFLOW;

            // Find matching pattern group for transaction count
            int txnCount = 0;
            BigDecimal totalAmount = BigDecimal.ZERO;
            for (PatternDeduplicator.PatternGroup pg : patternGroups) {
                if (pg.pattern().equalsIgnoreCase(dto.pattern) && pg.type() == type
                        && matchesBankCategory(pg.bankCategory(), dto.bankCategory)) {
                    txnCount += pg.transactionCount();
                    totalAmount = totalAmount.add(pg.totalAmount());
                }
            }

            result.add(new CategorizationSuggestions.ContextMapping(
                    dto.pattern,
                    dto.bankCategory != null ? dto.bankCategory : "",
                    dto.suggestedCategory,
                    dto.parentCategory,
                    type,
                    dto.confidence,
                    dto.dominantSignal != null ? dto.dominantSignal : "MERCHANT",
                    Boolean.TRUE.equals(dto.isExistingCategory),
                    dto.reason,
                    txnCount,
                    totalAmount
            ));
        }
        return result;
    }

    private boolean matchesBankCategory(String groupBankCat, String dtoBankCat) {
        if (groupBankCat == null && dtoBankCat == null) return true;
        if (groupBankCat == null || dtoBankCat == null) return false;
        return groupBankCat.equalsIgnoreCase(dtoBankCat);
    }

    private List<CategorizationSuggestions.BankCategoryFallback> convertBankCategoryFallbacks(
            List<BankCategoryFallbackDto> dtos,
            List<PatternDeduplicator.PatternGroup> patternGroups) {
        if (dtos == null || dtos.isEmpty()) {
            return List.of();
        }

        // Build set of actual bank categories from transaction data for validation
        Set<String> actualBankCategories = patternGroups.stream()
                .map(PatternDeduplicator.PatternGroup::bankCategory)
                .filter(bc -> bc != null && !bc.isBlank())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        List<CategorizationSuggestions.BankCategoryFallback> result = new ArrayList<>();
        for (BankCategoryFallbackDto dto : dtos) {
            if (dto.bankCategory == null || dto.defaultTarget == null) continue;

            // Validate that this bankCategory actually exists in our data
            if (!actualBankCategories.contains(dto.bankCategory.toLowerCase())) {
                log.warn("Filtering invalid bankCategoryFallback: '{}' not found in transaction data", dto.bankCategory);
                continue;
            }

            Type type = "INFLOW".equalsIgnoreCase(dto.type) ? Type.INFLOW : Type.OUTFLOW;

            result.add(new CategorizationSuggestions.BankCategoryFallback(
                    dto.bankCategory,
                    dto.defaultTarget,
                    dto.parentCategory,
                    type,
                    dto.confidence,
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
        private List<ContextMappingDto> contextMappings;
        private List<BankCategoryFallbackDto> bankCategoryFallbacks;
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

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContextMappingDto {
        private String pattern;
        private String bankCategory;
        private String suggestedCategory;
        private String parentCategory;
        private String type;
        private int confidence;
        private String dominantSignal;
        private Boolean isExistingCategory;
        private String reason;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BankCategoryFallbackDto {
        private String bankCategory;
        private String defaultTarget;
        private String parentCategory;
        private String type;
        private int confidence;
        private String reason;
    }

    // ============ Result wrapper ============

    public record ParseResult(
            boolean success,
            CategorizationSuggestions.SuggestedStructure structure,
            List<CategorizationSuggestions.PatternSuggestion> suggestions,
            List<CategorizationSuggestions.BankCategorySuggestion> bankCategorySuggestions,
            List<CategorizationSuggestions.UnrecognizedPattern> unrecognizedPatterns,
            List<CategorizationSuggestions.StructureOptimization> structureOptimizations,
            List<CategorizationSuggestions.ContextMapping> contextMappings,
            List<CategorizationSuggestions.BankCategoryFallback> bankCategoryFallbacks,
            String errorMessage
    ) {
        public static ParseResult success(
                CategorizationSuggestions.SuggestedStructure structure,
                List<CategorizationSuggestions.PatternSuggestion> suggestions,
                List<CategorizationSuggestions.BankCategorySuggestion> bankCategorySuggestions,
                List<CategorizationSuggestions.UnrecognizedPattern> unrecognizedPatterns,
                List<CategorizationSuggestions.StructureOptimization> structureOptimizations,
                List<CategorizationSuggestions.ContextMapping> contextMappings,
                List<CategorizationSuggestions.BankCategoryFallback> bankCategoryFallbacks) {
            return new ParseResult(true, structure, suggestions, bankCategorySuggestions, unrecognizedPatterns, structureOptimizations, contextMappings, bankCategoryFallbacks, null);
        }

        public static ParseResult error(String message) {
            return new ParseResult(
                    false,
                    CategorizationSuggestions.SuggestedStructure.empty(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    message
            );
        }
    }
}
