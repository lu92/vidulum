package com.multi.vidulum.bank_data_adapter.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multi.vidulum.bank_data_adapter.domain.MappingRules;
import com.multi.vidulum.bank_data_adapter.domain.MappingRules.ColumnMapping;
import com.multi.vidulum.bank_data_adapter.domain.MappingRules.TransformationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Processes AI response and extracts MappingRules.
 * Includes validation against pre-detected format to correct AI mistakes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiMappingRulesProcessor {

    private final ObjectMapper objectMapper;

    // Confidence threshold for overriding AI values
    private static final double OVERRIDE_CONFIDENCE_THRESHOLD = 0.7;

    /**
     * Process AI response with validation against pre-detected delimiter.
     * If AI returns delimiter that conflicts with high-confidence detection,
     * the detected value will be used instead.
     *
     * @param aiResponse Raw AI response (JSON)
     * @param bankIdentifier Computed bank identifier for this format
     * @param detectedDelimiter Pre-detected delimiter for validation (may be null)
     * @return Parsed and validated mapping rules result
     */
    public MappingRulesResult process(String aiResponse, String bankIdentifier,
                                       CsvFormatDetector.DetectedDelimiter detectedDelimiter) {
        // First, parse the AI response
        MappingRulesResult parseResult = parseAiResponse(aiResponse, bankIdentifier);

        if (!parseResult.success()) {
            return parseResult;
        }

        // If we have pre-detected delimiter, validate and potentially override
        if (detectedDelimiter != null && detectedDelimiter.confidence() > 0) {
            MappingRules validatedRules = validateAndCorrectRules(parseResult.rules(), detectedDelimiter);
            return MappingRulesResult.success(validatedRules);
        }

        return parseResult;
    }

    /**
     * Process AI response without format validation.
     *
     * @param aiResponse Raw AI response (JSON)
     * @param bankIdentifier Computed bank identifier for this format
     * @return Parsed mapping rules result
     */
    public MappingRulesResult process(String aiResponse, String bankIdentifier) {
        return process(aiResponse, bankIdentifier, null);
    }

    /**
     * Validates AI-generated rules against pre-detected delimiter.
     * Only delimiter is validated/overridden - AI determines everything else.
     */
    private MappingRules validateAndCorrectRules(MappingRules rules,
                                                   CsvFormatDetector.DetectedDelimiter detectedDelimiter) {
        List<String> warnings = new ArrayList<>(rules.getWarnings() != null ? rules.getWarnings() : List.of());
        boolean corrected = false;

        // Validate delimiter - this is the critical check
        // Wrong delimiter causes 0 rows to be parsed
        if (!rules.getDelimiter().equals(detectedDelimiter.delimiter())) {
            if (detectedDelimiter.confidence() >= OVERRIDE_CONFIDENCE_THRESHOLD) {
                log.warn("AI returned delimiter='{}' but statistical analysis found '{}' (confidence={:.0f}%) - OVERRIDING",
                    rules.getDelimiter(), detectedDelimiter.delimiter(), detectedDelimiter.confidence() * 100);
                warnings.add(String.format("Delimiter corrected: AI='%s', using statistically detected '%s'",
                    rules.getDelimiter(), detectedDelimiter.delimiter()));
                rules.setDelimiter(detectedDelimiter.delimiter());
                corrected = true;
            } else {
                log.info("Delimiter mismatch (AI='{}', detected='{}') but confidence too low ({:.0f}%) - using AI value",
                    rules.getDelimiter(), detectedDelimiter.delimiter(), detectedDelimiter.confidence() * 100);
            }
        }

        // Update confidence score if we made corrections
        if (corrected) {
            double blendedConfidence = (rules.getConfidenceScore() + detectedDelimiter.confidence()) / 2;
            rules.setConfidenceScore(blendedConfidence);
        }

        rules.setWarnings(warnings);
        return rules;
    }

    /**
     * Parse AI response into MappingRules.
     */
    private MappingRulesResult parseAiResponse(String aiResponse, String bankIdentifier) {
        if (aiResponse == null || aiResponse.isBlank()) {
            return MappingRulesResult.error("Empty AI response");
        }

        // Clean response - remove markdown code blocks if present
        String jsonContent = cleanJsonResponse(aiResponse);

        try {
            JsonNode root = objectMapper.readTree(jsonContent);

            // Check for error response
            if (root.has("error") && root.get("error").asBoolean()) {
                String errorCode = root.path("errorCode").asText("UNKNOWN_ERROR");
                String errorMessage = root.path("errorMessage").asText("Unknown error");
                return MappingRulesResult.error(errorCode + ": " + errorMessage);
            }

            // Parse mapping rules
            MappingRules rules = parseMappingRules(root, bankIdentifier);
            return MappingRulesResult.success(rules);

        } catch (JsonProcessingException e) {
            log.error("Failed to parse AI response as JSON: {}", e.getMessage());
            log.debug("Raw response: {}", aiResponse);
            return MappingRulesResult.error("Invalid JSON response: " + e.getMessage());
        } catch (Exception e) {
            log.error("Error processing AI response", e);
            return MappingRulesResult.error("Processing error: " + e.getMessage());
        }
    }

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

    private MappingRules parseMappingRules(JsonNode root, String bankIdentifier) {
        MappingRules rules = new MappingRules();

        rules.setId(UUID.randomUUID().toString());
        rules.setBankIdentifier(bankIdentifier);
        rules.setBankName(root.path("bankName").asText("Unknown Bank"));
        rules.setBankCountry(root.path("bankCountry").asText("PL"));
        rules.setLanguage(root.path("language").asText("pl"));
        rules.setDateFormat(root.path("dateFormat").asText("dd-MM-yyyy"));
        rules.setDelimiter(root.path("delimiter").asText(","));
        rules.setEncoding(root.path("encoding").asText("UTF-8"));
        rules.setHeaderRowIndex(root.path("headerRowIndex").asInt(0));
        rules.setMetadataRows(root.path("metadataRows").asInt(0));
        rules.setOriginalHeader(root.path("originalHeader").asText(""));
        rules.setConfidenceScore(root.path("confidenceScore").asDouble(0.8));
        rules.setSampleInputRow(root.path("sampleInputRow").asText(""));
        rules.setSampleOutputRow(root.path("sampleOutputRow").asText(""));
        rules.setUsageCount(0);
        rules.setCreatedAt(new Date());

        // Parse column mappings
        List<ColumnMapping> mappings = new ArrayList<>();
        JsonNode mappingsNode = root.path("columnMappings");

        if (mappingsNode.isArray()) {
            for (JsonNode mappingNode : mappingsNode) {
                ColumnMapping mapping = parseColumnMapping(mappingNode);
                if (mapping != null) {
                    mappings.add(mapping);
                }
            }
        }

        rules.setColumnMappings(mappings);

        // Parse warnings
        List<String> warnings = new ArrayList<>();
        JsonNode warningsNode = root.path("warnings");
        if (warningsNode.isArray()) {
            for (JsonNode warning : warningsNode) {
                warnings.add(warning.asText());
            }
        }
        rules.setWarnings(warnings);

        return rules;
    }

    private ColumnMapping parseColumnMapping(JsonNode node) {
        try {
            ColumnMapping mapping = new ColumnMapping();

            mapping.setSourceColumn(node.path("sourceColumn").asText(""));
            mapping.setSourceIndex(node.path("sourceIndex").asInt(-1));
            mapping.setTargetField(node.path("targetField").asText(""));
            mapping.setRequired(node.path("required").asBoolean(false));

            // Parse transformation type
            String typeStr = node.path("transformationType").asText("DIRECT");
            try {
                mapping.setTransformationType(TransformationType.valueOf(typeStr));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown transformation type: {}, defaulting to DIRECT", typeStr);
                mapping.setTransformationType(TransformationType.DIRECT);
            }

            // Parse transformation params
            Map<String, String> params = new HashMap<>();
            JsonNode paramsNode = node.path("transformationParams");
            if (paramsNode.isObject()) {
                paramsNode.fields().forEachRemaining(entry ->
                    params.put(entry.getKey(), entry.getValue().asText()));
            }
            mapping.setTransformationParams(params);

            return mapping;

        } catch (Exception e) {
            log.warn("Failed to parse column mapping: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Result of processing AI response for mapping rules.
     */
    public record MappingRulesResult(
        boolean success,
        MappingRules rules,
        String errorMessage
    ) {
        public static MappingRulesResult success(MappingRules rules) {
            return new MappingRulesResult(true, rules, null);
        }

        public static MappingRulesResult error(String message) {
            return new MappingRulesResult(false, null, message);
        }
    }
}
