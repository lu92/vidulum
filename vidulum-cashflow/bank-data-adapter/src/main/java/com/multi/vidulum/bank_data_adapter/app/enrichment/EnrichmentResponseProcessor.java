package com.multi.vidulum.bank_data_adapter.app.enrichment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multi.vidulum.bank_data_adapter.domain.TransactionClassification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Processes AI responses for enrichment.
 * Handles parsing, validation, and fallback for malformed responses.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EnrichmentResponseProcessor {

    private final ObjectMapper objectMapper;

    // Pattern to extract JSON from markdown code blocks
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile(
            "```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```",
            Pattern.MULTILINE
    );

    // Pattern to find JSON object
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile(
            "\\{[\\s\\S]*\\}",
            Pattern.MULTILINE
    );

    /**
     * Process AI response and extract enrichment results.
     *
     * @param aiResponse          Raw AI response text
     * @param originalTransactions Original transactions for fallback
     * @return Parsed batch result
     */
    public EnrichmentBatchResult process(String aiResponse, List<TransactionForEnrichment> originalTransactions) {
        if (aiResponse == null || aiResponse.isBlank()) {
            log.warn("Empty AI response, using fallback");
            return createFallbackResult(originalTransactions, "Empty AI response");
        }

        // Try to extract JSON from response
        String jsonContent = extractJson(aiResponse);

        if (jsonContent == null) {
            log.warn("Could not extract JSON from AI response, using fallback");
            return createFallbackResult(originalTransactions, "Could not extract JSON from response");
        }

        try {
            EnrichmentBatchResult result = objectMapper.readValue(jsonContent, EnrichmentBatchResult.class);

            // Validate result
            ValidationResult validation = validate(result, originalTransactions);
            if (!validation.valid) {
                log.warn("Validation failed: {}", validation.message);
                return repairOrFallback(result, originalTransactions, validation.message);
            }

            return result;

        } catch (JsonProcessingException e) {
            log.warn("Failed to parse AI response JSON: {}", e.getMessage());
            return createFallbackResult(originalTransactions, "JSON parse error: " + e.getMessage());
        }
    }

    /**
     * Extract JSON from AI response.
     * Handles markdown code blocks and raw JSON.
     */
    private String extractJson(String response) {
        // First, try to extract from markdown code block
        Matcher blockMatcher = JSON_BLOCK_PATTERN.matcher(response);
        if (blockMatcher.find()) {
            return blockMatcher.group(1).trim();
        }

        // Try to find raw JSON object
        Matcher objectMatcher = JSON_OBJECT_PATTERN.matcher(response);
        if (objectMatcher.find()) {
            return objectMatcher.group().trim();
        }

        // Response might be plain JSON
        String trimmed = response.trim();
        if (trimmed.startsWith("{")) {
            return trimmed;
        }

        return null;
    }

    /**
     * Validate enrichment result.
     * Uses global rowIndex values that match the original CSV positions.
     */
    private ValidationResult validate(EnrichmentBatchResult result, List<TransactionForEnrichment> originals) {
        if (result == null) {
            return new ValidationResult(false, "Result is null");
        }

        if (!result.isSuccess() && result.getErrorMessage() != null) {
            return new ValidationResult(false, "AI reported error: " + result.getErrorMessage());
        }

        if (result.getEnrichedTransactions() == null) {
            return new ValidationResult(false, "enrichedTransactions is null");
        }

        if (result.getEnrichedTransactions().size() != originals.size()) {
            return new ValidationResult(false,
                    String.format("Row count mismatch: expected %d, got %d",
                            originals.size(), result.getEnrichedTransactions().size()));
        }

        // Build set of valid rowIndexes from original batch (these are GLOBAL indexes)
        Set<Integer> validIndexes = originals.stream()
                .map(TransactionForEnrichment::getRowIndex)
                .collect(java.util.stream.Collectors.toSet());

        // Check for valid and unique rowIndexes
        Set<Integer> foundIndexes = new java.util.HashSet<>();
        for (var txn : result.getEnrichedTransactions()) {
            int idx = txn.getRowIndex();
            if (!validIndexes.contains(idx)) {
                return new ValidationResult(false, "Invalid rowIndex: " + idx +
                        " (expected one of: " + validIndexes.stream().sorted().limit(3).toList() + "...)");
            }
            if (foundIndexes.contains(idx)) {
                return new ValidationResult(false, "Duplicate rowIndex: " + idx);
            }
            foundIndexes.add(idx);
        }

        return new ValidationResult(true, "OK");
    }

    /**
     * Try to repair partial results or fallback.
     * Uses global rowIndex values that match the original CSV positions.
     */
    private EnrichmentBatchResult repairOrFallback(EnrichmentBatchResult partial,
                                                    List<TransactionForEnrichment> originals,
                                                    String validationMessage) {
        if (partial == null || partial.getEnrichedTransactions() == null) {
            return createFallbackResult(originals, validationMessage);
        }

        // Build map of valid rowIndex -> position in originals list
        Map<Integer, Integer> indexToPosition = new java.util.HashMap<>();
        for (int i = 0; i < originals.size(); i++) {
            indexToPosition.put(originals.get(i).getRowIndex(), i);
        }

        // Try to repair by filling missing transactions
        List<EnrichmentBatchResult.EnrichedTransactionJson> repaired = new ArrayList<>();
        Set<Integer> processedIndexes = new java.util.HashSet<>();

        // Add valid transactions from partial result
        for (var txn : partial.getEnrichedTransactions()) {
            int idx = txn.getRowIndex();
            if (indexToPosition.containsKey(idx) && !processedIndexes.contains(idx)) {
                repaired.add(txn);
                processedIndexes.add(idx);
            }
        }

        // Fill missing with fallback - use global rowIndex
        for (var original : originals) {
            if (!processedIndexes.contains(original.getRowIndex())) {
                repaired.add(createFallbackTransaction(original));
            }
        }

        return EnrichmentBatchResult.builder()
                .success(true)
                .enrichedTransactions(repaired)
                .processingNotes("Repaired partial result: " + validationMessage)
                .build();
    }

    /**
     * Create complete fallback result when AI fails.
     */
    private EnrichmentBatchResult createFallbackResult(List<TransactionForEnrichment> originals, String reason) {
        List<EnrichmentBatchResult.EnrichedTransactionJson> fallbacks = originals.stream()
                .map(this::createFallbackTransaction)
                .toList();

        return EnrichmentBatchResult.builder()
                .success(true) // Mark as success so processing continues
                .enrichedTransactions(fallbacks)
                .processingNotes("Fallback used: " + reason)
                .build();
    }

    /**
     * Create fallback for single transaction.
     */
    private EnrichmentBatchResult.EnrichedTransactionJson createFallbackTransaction(TransactionForEnrichment original) {
        String fallbackMerchant = extractFallbackMerchant(original.getName());
        String bankCategory = original.getBankCategory();
        String source;

        if (bankCategory == null || bankCategory.isBlank()) {
            bankCategory = "Inne";
            source = "FALLBACK_ERROR";
        } else {
            source = "ORIGINAL";
        }

        return EnrichmentBatchResult.EnrichedTransactionJson.builder()
                .rowIndex(original.getRowIndex())
                .classification("UNKNOWN")  // Default classification for fallback
                .merchant(fallbackMerchant)
                .merchantConfidence(0.1)
                .bankCategory(bankCategory)
                .bankCategorySource(source)
                .classificationReason("Fallback - AI processing failed")
                .location(null)
                .build();
    }

    /**
     * Extract fallback merchant from name.
     * Takes first word, cleans it, uppercases.
     */
    private String extractFallbackMerchant(String name) {
        if (name == null || name.isBlank()) {
            return "UNKNOWN";
        }

        // Clean and get first meaningful word
        String cleaned = name.toUpperCase()
                .replaceAll("[^A-ZĄĆĘŁŃÓŚŹŻ\\s]", " ")
                .trim();

        // Skip common prefixes
        String[] skipPrefixes = {"PRZELEW", "OD", "DO", "NA", "BANK", "PŁATNOŚĆ", "KARTĄ", "BLIK"};
        String[] words = cleaned.split("\\s+");

        for (String word : words) {
            if (word.length() < 2) continue;

            boolean skip = false;
            for (String prefix : skipPrefixes) {
                if (word.equals(prefix)) {
                    skip = true;
                    break;
                }
            }

            if (!skip) {
                return word.length() > 30 ? word.substring(0, 30) : word;
            }
        }

        // If nothing found, use first 30 chars
        return cleaned.length() > 30 ? cleaned.substring(0, 30) : cleaned;
    }

    /**
     * Convert batch result to domain objects.
     */
    public List<EnrichedTransaction> toDomainObjects(EnrichmentBatchResult batchResult) {
        if (batchResult == null || batchResult.getEnrichedTransactions() == null) {
            return List.of();
        }

        return batchResult.getEnrichedTransactions().stream()
                .map(json -> EnrichedTransaction.builder()
                        .rowIndex(json.getRowIndex())
                        .classification(parseClassification(json.getClassification()))
                        .merchant(json.getMerchant())
                        .merchantConfidence(json.getMerchantConfidence())
                        .bankCategory(json.getBankCategory())
                        .bankCategorySource(parseSource(json.getBankCategorySource()))
                        .classificationReason(json.getClassificationReason())
                        .location(json.getLocation())
                        .build())
                .toList();
    }

    private TransactionClassification parseClassification(String classification) {
        return TransactionClassification.fromString(classification);
    }

    private EnrichedTransaction.BankCategorySource parseSource(String source) {
        if (source == null) {
            return EnrichedTransaction.BankCategorySource.AI_FALLBACK;
        }
        try {
            return EnrichedTransaction.BankCategorySource.valueOf(source);
        } catch (IllegalArgumentException e) {
            return EnrichedTransaction.BankCategorySource.AI_FALLBACK;
        }
    }

    private record ValidationResult(boolean valid, String message) {}
}
