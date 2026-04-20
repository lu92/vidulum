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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

    @Value("${bank-data-adapter.enrichment.max-parallelism:4}")
    private int maxParallelism;

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

        // Group transactions by exact match on name (reduces AI calls)
        Map<String, TransactionGroup> groups = groupTransactions(transactions);
        List<TransactionForEnrichment> representatives = groups.values().stream()
                .map(TransactionGroup::getRepresentative)
                .toList();

        log.info("Starting enrichment for {} transactions ({} unique groups, bank: {}, language: {})",
                transactions.size(), groups.size(), bankName, language);

        // Split representatives into batches (not all transactions)
        List<List<TransactionForEnrichment>> batches = partition(representatives, batchSize);
        log.info("Split into {} batches of up to {} representatives each (parallelism: {})",
                batches.size(), batchSize, maxParallelism);

        // Process batches in parallel
        List<EnrichedTransaction> enrichedRepresentatives = Collections.synchronizedList(new ArrayList<>());
        List<String> allWarnings = Collections.synchronizedList(new ArrayList<>());
        StringBuilder processingNotes = new StringBuilder();
        int totalInputTokens = 0;
        int totalOutputTokens = 0;

        if (batches.size() == 1) {
            // Single batch - no parallelism needed
            processSingleBatch(batches.get(0), 1, 1, bankName, language,
                    enrichedRepresentatives, allWarnings, processingNotes);
        } else {
            // Multiple batches - process in parallel
            processInParallel(batches, bankName, language,
                    enrichedRepresentatives, allWarnings, processingNotes);
        }

        // Propagate results from representatives to all transactions (with selective bankCategory)
        List<EnrichedTransaction> allEnriched = propagateResults(enrichedRepresentatives, groups, transactions);

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

        log.info("Enrichment completed: {} transactions, {} groups, {} merchants, {} inferred, {} kept, {} fallbacks, {}ms",
                transactions.size(), groups.size(), merchantsExtracted, bankCategoriesInferred,
                bankCategoriesKept, fallbackCount, processingTimeMs);

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
     * Process a single batch synchronously (used when only 1 batch).
     */
    private void processSingleBatch(List<TransactionForEnrichment> batch,
                                     int batchNumber,
                                     int totalBatches,
                                     String bankName,
                                     String language,
                                     List<EnrichedTransaction> results,
                                     List<String> warnings,
                                     StringBuilder notes) {
        log.info("Processing single batch ({} representatives)", batch.size());
        try {
            EnrichmentBatchResult batchResult = processBatch(batch, batchNumber, totalBatches, bankName, language);
            results.addAll(responseProcessor.toDomainObjects(batchResult));
            if (batchResult.getProcessingNotes() != null && !batchResult.getProcessingNotes().isBlank()) {
                synchronized (notes) {
                    notes.append("Batch 1: ").append(batchResult.getProcessingNotes()).append("\n");
                }
            }
        } catch (Exception e) {
            log.error("Failed to process batch", e);
            warnings.add("Batch 1 failed: " + e.getMessage());
            EnrichmentBatchResult fallback = responseProcessor.process(null, batch);
            results.addAll(responseProcessor.toDomainObjects(fallback));
        }
    }

    /**
     * Process multiple batches in parallel using CompletableFuture.
     */
    private void processInParallel(List<List<TransactionForEnrichment>> batches,
                                    String bankName,
                                    String language,
                                    List<EnrichedTransaction> results,
                                    List<String> warnings,
                                    StringBuilder notes) {
        int effectiveParallelism = Math.min(maxParallelism, batches.size());
        ExecutorService executor = Executors.newFixedThreadPool(effectiveParallelism);

        log.info("Processing {} batches in parallel (threads: {})", batches.size(), effectiveParallelism);
        long parallelStart = System.currentTimeMillis();

        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (int i = 0; i < batches.size(); i++) {
                final int batchIndex = i;
                final List<TransactionForEnrichment> batch = batches.get(i);

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    log.info("Processing batch {}/{} ({} representatives) [thread: {}]",
                            batchIndex + 1, batches.size(), batch.size(), Thread.currentThread().getName());

                    try {
                        EnrichmentBatchResult batchResult = processBatch(
                                batch, batchIndex + 1, batches.size(), bankName, language);

                        results.addAll(responseProcessor.toDomainObjects(batchResult));

                        if (batchResult.getProcessingNotes() != null && !batchResult.getProcessingNotes().isBlank()) {
                            synchronized (notes) {
                                notes.append("Batch ").append(batchIndex + 1).append(": ")
                                        .append(batchResult.getProcessingNotes()).append("\n");
                            }
                        }

                        log.info("Batch {}/{} completed", batchIndex + 1, batches.size());

                    } catch (Exception e) {
                        log.error("Failed to process batch {}", batchIndex + 1, e);
                        warnings.add("Batch " + (batchIndex + 1) + " failed: " + e.getMessage());

                        // Use fallback for failed batch
                        EnrichmentBatchResult fallback = responseProcessor.process(null, batch);
                        results.addAll(responseProcessor.toDomainObjects(fallback));
                    }
                }, executor);

                futures.add(future);
            }

            // Wait for all batches to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(5, TimeUnit.MINUTES); // Timeout after 5 minutes

            long parallelTime = System.currentTimeMillis() - parallelStart;
            log.info("All {} batches completed in {}ms (parallel)", batches.size(), parallelTime);

        } catch (Exception e) {
            log.error("Parallel batch processing failed", e);
            warnings.add("Parallel processing error: " + e.getMessage());
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
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

    /**
     * Group transactions by exact match on normalized name.
     * This reduces AI calls by processing only unique transaction patterns.
     *
     * @param transactions All transactions to enrich
     * @return Map of group key to TransactionGroup
     */
    Map<String, TransactionGroup> groupTransactions(List<TransactionForEnrichment> transactions) {
        Map<String, TransactionGroup> groups = new HashMap<>();

        for (TransactionForEnrichment txn : transactions) {
            String groupKey = normalizeGroupKey(txn.getName());

            TransactionGroup group = groups.computeIfAbsent(groupKey, key ->
                    TransactionGroup.builder()
                            .groupKey(key)
                            .build()
            );

            group.addTransaction(txn);
        }

        return groups;
    }

    /**
     * Normalize name for grouping (exact match, case-insensitive).
     */
    private String normalizeGroupKey(String name) {
        if (name == null) {
            return "";
        }
        return name.toUpperCase().trim();
    }

    /**
     * Propagate enrichment results from representatives to all transactions in groups.
     * Uses selective propagation for bankCategory (only if original was empty).
     *
     * @param enrichedRepresentatives List of enriched representative transactions
     * @param groups                  Map of group key to TransactionGroup
     * @param allTransactions         All original transactions
     * @return List of enriched transactions for all rows
     */
    List<EnrichedTransaction> propagateResults(
            List<EnrichedTransaction> enrichedRepresentatives,
            Map<String, TransactionGroup> groups,
            List<TransactionForEnrichment> allTransactions) {

        // Build map of representative rowIndex -> enriched result
        Map<Integer, EnrichedTransaction> representativeResults = enrichedRepresentatives.stream()
                .collect(Collectors.toMap(EnrichedTransaction::getRowIndex, e -> e));

        // Build map of groupKey -> representative rowIndex for lookup
        Map<String, Integer> groupKeyToRepresentativeIdx = new HashMap<>();
        for (TransactionGroup group : groups.values()) {
            groupKeyToRepresentativeIdx.put(
                    group.getGroupKey(),
                    group.getRepresentative().getRowIndex()
            );
        }

        List<EnrichedTransaction> allEnriched = new ArrayList<>();

        for (TransactionForEnrichment txn : allTransactions) {
            String groupKey = normalizeGroupKey(txn.getName());
            TransactionGroup group = groups.get(groupKey);
            Integer representativeIdx = groupKeyToRepresentativeIdx.get(groupKey);
            EnrichedTransaction representativeResult = representativeResults.get(representativeIdx);

            if (representativeResult == null) {
                // Fallback: no result for this group (shouldn't happen, but defensive)
                log.warn("No enrichment result for group: {}", groupKey);
                allEnriched.add(EnrichedTransaction.builder()
                        .rowIndex(txn.getRowIndex())
                        .merchant(txn.getName())
                        .merchantConfidence(0.1)
                        .bankCategory(txn.getBankCategory() != null && !txn.getBankCategory().isBlank()
                                ? txn.getBankCategory() : "Inne")
                        .bankCategorySource(EnrichedTransaction.BankCategorySource.AI_FALLBACK)
                        .build());
                continue;
            }

            // Propagate merchant (always)
            String merchant = representativeResult.getMerchant();
            double merchantConfidence = representativeResult.getMerchantConfidence();

            // Selective propagation for bankCategory
            String bankCategory;
            EnrichedTransaction.BankCategorySource bankCategorySource;

            if (group.hadEmptyBankCategory(txn.getRowIndex())) {
                // Original was empty - use AI result
                bankCategory = representativeResult.getBankCategory();
                bankCategorySource = representativeResult.getBankCategorySource();
            } else {
                // Original had value - keep it
                bankCategory = txn.getBankCategory();
                bankCategorySource = EnrichedTransaction.BankCategorySource.ORIGINAL;
            }

            allEnriched.add(EnrichedTransaction.builder()
                    .rowIndex(txn.getRowIndex())
                    .merchant(merchant)
                    .merchantConfidence(merchantConfidence)
                    .bankCategory(bankCategory)
                    .bankCategorySource(bankCategorySource)
                    .build());
        }

        return allEnriched;
    }
}
