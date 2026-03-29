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
            AiCategorizationResult.SuggestedStructure structure = convertStructure(dto.categoryStructure);
            List<AiCategorizationResult.PatternSuggestion> suggestions =
                    convertMappings(dto.patternMappings, patternGroups);

            return ParseResult.success(structure, suggestions);

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
                    totalAmount
            ));
        }

        return suggestions;
    }

    // ============ DTOs for JSON parsing ============

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AiResponseDto {
        private CategoryStructureDto categoryStructure;
        private List<PatternMappingDto> patternMappings;
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
    }

    // ============ Result wrapper ============

    public record ParseResult(
            boolean success,
            AiCategorizationResult.SuggestedStructure structure,
            List<AiCategorizationResult.PatternSuggestion> suggestions,
            String errorMessage
    ) {
        public static ParseResult success(
                AiCategorizationResult.SuggestedStructure structure,
                List<AiCategorizationResult.PatternSuggestion> suggestions) {
            return new ParseResult(true, structure, suggestions, null);
        }

        public static ParseResult error(String message) {
            return new ParseResult(
                    false,
                    AiCategorizationResult.SuggestedStructure.empty(),
                    List.of(),
                    message
            );
        }
    }
}
