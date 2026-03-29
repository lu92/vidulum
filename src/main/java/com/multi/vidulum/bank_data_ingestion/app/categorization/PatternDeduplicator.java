package com.multi.vidulum.bank_data_ingestion.app.categorization;

import com.multi.vidulum.bank_data_ingestion.domain.StagedTransaction;
import com.multi.vidulum.cashflow.domain.Type;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Groups transactions by normalized pattern.
 *
 * Takes N transactions and deduplicates them into M unique patterns (M << N).
 * This reduces the number of patterns sent to AI, significantly lowering costs.
 *
 * Example:
 * - Input: 402 transactions
 * - Output: 45 unique patterns
 */
@Component
@RequiredArgsConstructor
public class PatternDeduplicator {

    private final TransactionNameNormalizer normalizer;

    /**
     * Groups staged transactions by their normalized pattern.
     *
     * @param transactions the staged transactions to group
     * @return a list of pattern groups, sorted by transaction count (descending)
     */
    public List<PatternGroup> deduplicate(List<StagedTransaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return List.of();
        }

        // Group transactions by normalized pattern + type
        Map<PatternKey, List<TransactionInfo>> groups = new HashMap<>();

        for (StagedTransaction transaction : transactions) {
            String originalName = transaction.originalData().name();
            String normalizedPattern = normalizer.normalize(originalName);
            Type type = transaction.originalData().type();

            PatternKey key = new PatternKey(normalizedPattern, type);

            groups.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new TransactionInfo(
                            transaction.stagedTransactionId().id(),
                            originalName,
                            transaction.originalData().description(),
                            transaction.originalData().money().getAmount(),
                            transaction.originalData().bankCategory()
                    ));
        }

        // Convert to PatternGroup objects
        return groups.entrySet().stream()
                .map(entry -> createPatternGroup(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(PatternGroup::transactionCount).reversed())
                .toList();
    }

    private PatternGroup createPatternGroup(PatternKey key, List<TransactionInfo> transactions) {
        // Find the most representative sample (longest original name)
        String sampleTransaction = transactions.stream()
                .max(Comparator.comparingInt(t -> t.originalName().length()))
                .map(TransactionInfo::originalName)
                .orElse("");

        // Find the most informative description (longest non-blank description)
        String sampleDescription = transactions.stream()
                .map(TransactionInfo::description)
                .filter(d -> d != null && !d.isBlank())
                .max(Comparator.comparingInt(String::length))
                .orElse("");

        // Calculate total amount
        BigDecimal totalAmount = transactions.stream()
                .map(TransactionInfo::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Get most common bank category
        String mostCommonBankCategory = transactions.stream()
                .collect(Collectors.groupingBy(TransactionInfo::bankCategory, Collectors.counting()))
                .entrySet().stream()
                .max(Comparator.comparingLong(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse("");

        // Collect all transaction IDs
        List<String> transactionIds = transactions.stream()
                .map(TransactionInfo::transactionId)
                .toList();

        return new PatternGroup(
                key.pattern(),
                sampleTransaction,
                sampleDescription,
                key.type(),
                transactions.size(),
                totalAmount,
                mostCommonBankCategory,
                transactionIds
        );
    }

    /**
     * Key for grouping transactions.
     */
    private record PatternKey(String pattern, Type type) {
    }

    /**
     * Internal transaction info for grouping.
     */
    private record TransactionInfo(
            String transactionId,
            String originalName,
            String description,
            BigDecimal amount,
            String bankCategory
    ) {
    }

    /**
     * A group of transactions with the same normalized pattern.
     */
    public record PatternGroup(
            String pattern,
            String sampleTransaction,
            String sampleDescription,
            Type type,
            int transactionCount,
            BigDecimal totalAmount,
            String bankCategory,
            List<String> transactionIds
    ) {
        /**
         * Returns a shortened pattern for display (max 30 chars).
         */
        public String displayPattern() {
            if (pattern.length() <= 30) {
                return pattern;
            }
            return pattern.substring(0, 27) + "...";
        }

        /**
         * Checks if this is a high-value pattern (worth special attention).
         */
        public boolean isHighValue() {
            return totalAmount.compareTo(BigDecimal.valueOf(10000)) > 0;
        }

        /**
         * Checks if this pattern appears frequently.
         */
        public boolean isFrequent() {
            return transactionCount >= 5;
        }
    }
}
