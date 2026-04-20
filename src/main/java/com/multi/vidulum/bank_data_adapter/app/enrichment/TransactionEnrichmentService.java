package com.multi.vidulum.bank_data_adapter.app.enrichment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for enriching transactions with merchant and bankCategory.
 *
 * Architecture:
 * 1. Parse canonical CSV to transactions
 * 2. Check if enrichment is needed (any empty merchant or bankCategory)
 * 3. Batch transactions (default 50 per batch)
 * 4. Call AI for each batch
 * 5. Merge results and update CSV
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionEnrichmentService {

    private final ChatModel chatModel;
    private final EnrichmentPromptBuilder promptBuilder;
    private final EnrichmentResponseProcessor responseProcessor;

    @Value("${bank-data-adapter.enrichment.batch-size:50}")
    private int batchSize;

    @Value("${bank-data-adapter.enrichment.enabled:true}")
    private boolean enrichmentEnabled;

    /**
     * Check if enrichment is needed for the CSV.
     * Returns true if any transaction has empty merchant or bankCategory.
     */
    public boolean needsEnrichment(String csvContent) {
        if (!enrichmentEnabled) {
            log.debug("Enrichment disabled by configuration");
            return false;
        }

        List<TransactionForEnrichment> transactions = parseCsv(csvContent);

        // Count transactions needing enrichment
        long emptyMerchants = transactions.stream()
                .filter(t -> t.getName() == null || t.getName().isBlank())
                .count();

        // We always need enrichment because merchant field is always empty in current system
        // Also check bankCategory for banks like Nest that don't provide it
        long emptyBankCategories = transactions.stream()
                .filter(TransactionForEnrichment::needsBankCategoryInference)
                .count();

        log.info("Enrichment check: {} transactions, {} need merchant extraction, {} need bankCategory inference",
                transactions.size(), transactions.size(), emptyBankCategories);

        // Always enrich - we need merchant for all transactions
        return !transactions.isEmpty();
    }

    /**
     * Enrich transactions in the CSV.
     *
     * @param csvContent Canonical CSV content
     * @param bankName   Detected bank name
     * @param language   Detected language
     * @return Enrichment result with updated CSV
     */
    public EnrichmentResult enrich(String csvContent, String bankName, String language) {
        long startTime = System.currentTimeMillis();

        List<TransactionForEnrichment> transactions = parseCsv(csvContent);

        if (transactions.isEmpty()) {
            log.info("No transactions to enrich");
            return EnrichmentResult.noEnrichmentNeeded(csvContent, 0);
        }

        log.info("Starting enrichment for {} transactions (bank: {}, language: {})",
                transactions.size(), bankName, language);

        // Split into batches
        List<List<TransactionForEnrichment>> batches = partition(transactions, batchSize);
        log.info("Split into {} batches of up to {} transactions each", batches.size(), batchSize);

        // Process each batch
        List<EnrichedTransaction> allEnriched = new ArrayList<>();
        List<String> allWarnings = new ArrayList<>();
        StringBuilder processingNotes = new StringBuilder();
        int totalInputTokens = 0;
        int totalOutputTokens = 0;

        for (int i = 0; i < batches.size(); i++) {
            List<TransactionForEnrichment> batch = batches.get(i);
            log.info("Processing batch {}/{} ({} transactions)", i + 1, batches.size(), batch.size());

            try {
                EnrichmentBatchResult batchResult = processBatch(
                        batch, i + 1, batches.size(), bankName, language);

                List<EnrichedTransaction> enriched = responseProcessor.toDomainObjects(batchResult);
                allEnriched.addAll(enriched);

                if (batchResult.getProcessingNotes() != null && !batchResult.getProcessingNotes().isBlank()) {
                    processingNotes.append("Batch ").append(i + 1).append(": ")
                            .append(batchResult.getProcessingNotes()).append("\n");
                }

                // TODO: Extract token usage from ChatResponse when available

            } catch (Exception e) {
                log.error("Failed to process batch {}", i + 1, e);
                allWarnings.add("Batch " + (i + 1) + " failed: " + e.getMessage());

                // Use fallback for failed batch
                EnrichmentBatchResult fallback = responseProcessor.process(null, batch);
                allEnriched.addAll(responseProcessor.toDomainObjects(fallback));
            }
        }

        // Apply enrichment to CSV
        String enrichedCsv = applyCsvEnrichment(csvContent, allEnriched);

        // Calculate statistics
        int merchantsExtracted = (int) allEnriched.stream()
                .filter(e -> e.getMerchant() != null && !e.getMerchant().isBlank())
                .count();

        int bankCategoriesInferred = (int) allEnriched.stream()
                .filter(e -> e.getBankCategorySource() == EnrichedTransaction.BankCategorySource.AI_INFERRED)
                .count();

        int bankCategoriesKept = (int) allEnriched.stream()
                .filter(e -> e.getBankCategorySource() == EnrichedTransaction.BankCategorySource.ORIGINAL)
                .count();

        int fallbackCount = (int) allEnriched.stream()
                .filter(e -> e.getMerchantConfidence() < 0.3)
                .count();

        long processingTimeMs = System.currentTimeMillis() - startTime;

        log.info("Enrichment completed: {} merchants extracted, {} categories inferred, {} kept, {} fallbacks, {}ms",
                merchantsExtracted, bankCategoriesInferred, bankCategoriesKept, fallbackCount, processingTimeMs);

        return EnrichmentResult.builder()
                .enrichmentApplied(true)
                .enrichedCsvContent(enrichedCsv)
                .totalTransactions(transactions.size())
                .merchantsExtracted(merchantsExtracted)
                .bankCategoriesInferred(bankCategoriesInferred)
                .bankCategoriesKept(bankCategoriesKept)
                .fallbackCount(fallbackCount)
                .enrichedTransactions(allEnriched)
                .warnings(allWarnings)
                .processingTimeMs(processingTimeMs)
                .aiCallCount(batches.size())
                .totalInputTokens(totalInputTokens)
                .totalOutputTokens(totalOutputTokens)
                .processingNotes(processingNotes.toString().trim())
                .build();
    }

    /**
     * Process a single batch of transactions.
     */
    private EnrichmentBatchResult processBatch(List<TransactionForEnrichment> batch,
                                                int batchNumber,
                                                int totalBatches,
                                                String bankName,
                                                String language) {
        String systemPrompt = promptBuilder.getSystemPrompt();
        String userPrompt = promptBuilder.buildUserPrompt(batch, batchNumber, totalBatches, bankName, language);

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt)
        ));

        ChatResponse response = chatModel.call(prompt);
        String aiOutput = response.getResult().getOutput().getText();

        log.debug("AI response for batch {}: {} chars", batchNumber, aiOutput.length());

        return responseProcessor.process(aiOutput, batch);
    }

    /**
     * Parse canonical CSV to transactions for enrichment.
     */
    private List<TransactionForEnrichment> parseCsv(String csvContent) {
        if (csvContent == null || csvContent.isBlank()) {
            return List.of();
        }

        String[] lines = csvContent.split("\n");
        if (lines.length < 2) {
            return List.of();
        }

        // Parse header
        String headerLine = lines[0];
        String[] headers = parseRow(headerLine);
        int nameIdx = findIndex(headers, "name");
        int descIdx = findIndex(headers, "description");
        int bankCatIdx = findIndex(headers, "bankCategory");

        if (nameIdx < 0) {
            log.warn("Missing required column in CSV header: name={}", nameIdx);
            return List.of();
        }

        List<TransactionForEnrichment> transactions = new ArrayList<>();

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            String[] values = parseRow(line);

            try {
                TransactionForEnrichment txn = TransactionForEnrichment.builder()
                        .rowIndex(i - 1) // 0-based index after header
                        .name(getValueSafe(values, nameIdx))
                        .description(getValueSafe(values, descIdx))
                        .bankCategory(getValueSafe(values, bankCatIdx))
                        .build();

                transactions.add(txn);
            } catch (Exception e) {
                log.warn("Failed to parse row {}: {}", i, e.getMessage());
            }
        }

        return transactions;
    }

    /**
     * Apply enrichment results to CSV.
     */
    private String applyCsvEnrichment(String csvContent, List<EnrichedTransaction> enriched) {
        String[] lines = csvContent.split("\n");
        if (lines.length < 2) {
            return csvContent;
        }

        // Parse header and find column indexes
        String headerLine = lines[0];
        String[] headers = parseRow(headerLine);

        int merchantIdx = findIndex(headers, "merchant");
        int merchantConfIdx = findIndex(headers, "merchantConfidence");
        int bankCatIdx = findIndex(headers, "bankCategory");

        // Build index map for quick lookup
        java.util.Map<Integer, EnrichedTransaction> enrichmentMap = enriched.stream()
                .collect(Collectors.toMap(EnrichedTransaction::getRowIndex, e -> e));

        StringBuilder result = new StringBuilder();
        result.append(headerLine).append("\n");

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            int rowIndex = i - 1;
            EnrichedTransaction enrichment = enrichmentMap.get(rowIndex);

            if (enrichment != null) {
                String[] values = parseRow(line);

                // Update merchant
                if (merchantIdx >= 0 && merchantIdx < values.length) {
                    values[merchantIdx] = enrichment.getMerchant();
                }

                // Update merchantConfidence
                if (merchantConfIdx >= 0 && merchantConfIdx < values.length) {
                    values[merchantConfIdx] = String.valueOf(enrichment.getMerchantConfidence());
                }

                // Update bankCategory only if it was inferred (original was empty)
                if (bankCatIdx >= 0 && bankCatIdx < values.length) {
                    if (enrichment.getBankCategorySource() != EnrichedTransaction.BankCategorySource.ORIGINAL) {
                        values[bankCatIdx] = enrichment.getBankCategory();
                    }
                }

                result.append(formatRow(values)).append("\n");
            } else {
                result.append(line).append("\n");
            }
        }

        return result.toString().trim();
    }

    /**
     * Parse a CSV row handling quoted values.
     */
    private String[] parseRow(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++; // Skip next quote
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                values.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        values.add(current.toString().trim());

        return values.toArray(new String[0]);
    }

    /**
     * Format values back to CSV row.
     */
    private String formatRow(String[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(",");
            String value = values[i];
            if (value == null) {
                value = "";
            }
            // Quote if contains comma, quote, or newline
            if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
                sb.append("\"").append(value.replace("\"", "\"\"")).append("\"");
            } else {
                sb.append(value);
            }
        }
        return sb.toString();
    }

    private int findIndex(String[] headers, String name) {
        for (int i = 0; i < headers.length; i++) {
            if (headers[i].equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    private String getValueSafe(String[] values, int index) {
        if (index < 0 || index >= values.length) {
            return "";
        }
        return values[index];
    }

    /**
     * Partition list into batches.
     */
    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}
