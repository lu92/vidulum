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
 * Uses hybrid grouping:
 * 1. First groups by normalized pattern + type
 * 2. Then merges groups that share the same counterpartyAccount (bank account number)
 *
 * This solves the "Mindbox problem" where the same recipient appears with different
 * name variants (e.g., "MINDBOX SP. Z O.O.", "MINDBOX SP.Z O.O.") but same bank account.
 *
 * Example:
 * - Input: 402 transactions
 * - Output: 45 unique patterns (after merging by counterpartyAccount)
 */
@Component
@RequiredArgsConstructor
public class PatternDeduplicator {

    private final TransactionNameNormalizer normalizer;

    /**
     * Groups staged transactions by their normalized pattern.
     *
     * IMPORTANT: When merchant is available (extracted by AI), use it for grouping
     * instead of the name. This separates transactions like "BANK PEKAO S.A."
     * into individual merchants (BADOO, NETFLIX, OPENAI, etc.).
     *
     * After initial grouping, performs hybrid merging by counterpartyAccount
     * to merge groups that have the same bank account but different name patterns.
     *
     * @param transactions the staged transactions to group
     * @return a list of pattern groups, sorted by transaction count (descending)
     */
    public List<PatternGroup> deduplicate(List<StagedTransaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return List.of();
        }

        // Group transactions by normalized pattern + type
        // KEY CHANGE: Use merchant when available, otherwise fallback to name
        Map<PatternKey, List<TransactionInfo>> groups = new HashMap<>();

        for (StagedTransaction transaction : transactions) {
            String originalName = transaction.originalData().name();
            String merchant = transaction.originalData().merchant();
            Double merchantConfidence = transaction.originalData().merchantConfidence();
            String counterpartyAccount = transaction.originalData().counterpartyAccount();

            // Use effectiveMerchant for grouping: merchant if available, otherwise name
            String patternSource = transaction.originalData().effectiveMerchant();
            String normalizedPattern = normalizer.normalize(patternSource);
            Type type = transaction.originalData().type();

            PatternKey key = new PatternKey(normalizedPattern, type);

            groups.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new TransactionInfo(
                            transaction.stagedTransactionId().id(),
                            originalName,
                            transaction.originalData().description(),
                            transaction.originalData().money().getAmount(),
                            transaction.originalData().bankCategory(),
                            merchant,
                            merchantConfidence,
                            counterpartyAccount
                    ));
        }

        // Convert to PatternGroup objects
        List<PatternGroup> patternGroups = groups.entrySet().stream()
                .map(entry -> createPatternGroup(entry.getKey(), entry.getValue()))
                .collect(Collectors.toCollection(ArrayList::new));

        // Phase 2: Merge groups by counterpartyAccount (hybrid grouping)
        List<PatternGroup> mergedGroups = mergeGroupsByCounterpartyAccount(patternGroups);

        // Sort by transaction count (descending)
        return mergedGroups.stream()
                .sorted(Comparator.comparingInt(PatternGroup::transactionCount).reversed())
                .toList();
    }

    private PatternGroup createPatternGroup(PatternKey key, List<TransactionInfo> transactions) {
        // Find the most representative sample (longest original name)
        String sampleTransaction = transactions.stream()
                .max(Comparator.comparingInt(t -> t.originalName().length()))
                .map(TransactionInfo::originalName)
                .orElse("");

        // Find sample merchant (first non-null merchant with highest confidence)
        String sampleMerchant = transactions.stream()
                .filter(t -> t.merchant() != null && !t.merchant().isBlank())
                .max(Comparator.comparingDouble(t -> t.merchantConfidence() != null ? t.merchantConfidence() : 0.0))
                .map(TransactionInfo::merchant)
                .orElse(null);

        // Calculate average merchant confidence for transactions that have it
        Double averageMerchantConfidence = transactions.stream()
                .filter(t -> t.merchantConfidence() != null)
                .mapToDouble(TransactionInfo::merchantConfidence)
                .average()
                .orElse(0.0);
        // Set to null if no transactions had confidence
        if (transactions.stream().noneMatch(t -> t.merchantConfidence() != null)) {
            averageMerchantConfidence = null;
        }

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
                .filter(t -> t.bankCategory() != null)
                .collect(Collectors.groupingBy(TransactionInfo::bankCategory, Collectors.counting()))
                .entrySet().stream()
                .max(Comparator.comparingLong(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse("");

        // Collect all transaction IDs
        List<String> transactionIds = transactions.stream()
                .map(TransactionInfo::transactionId)
                .toList();

        // Get most common counterpartyAccount (for hybrid grouping)
        String mostCommonCounterpartyAccount = transactions.stream()
                .map(TransactionInfo::counterpartyAccount)
                .filter(acc -> acc != null && !acc.isBlank())
                .collect(Collectors.groupingBy(acc -> acc, Collectors.counting()))
                .entrySet().stream()
                .max(Comparator.comparingLong(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse(null);

        return new PatternGroup(
                key.pattern(),
                sampleTransaction,
                sampleMerchant,
                averageMerchantConfidence,
                sampleDescription,
                key.type(),
                transactions.size(),
                totalAmount,
                mostCommonBankCategory,
                transactionIds,
                mostCommonCounterpartyAccount
        );
    }

    /**
     * Merges pattern groups that share the same counterpartyAccount.
     *
     * This solves the "Mindbox problem" where the same recipient appears with different
     * name variants (e.g., "MINDBOX SP. Z O.O.", "MINDBOX SP.Z O.O.") but same bank account.
     *
     * Algorithm:
     * 1. Group patterns by (counterpartyAccount, type) - only non-null accounts
     * 2. If multiple patterns share the same account, merge them
     * 3. Use common prefix or shortest pattern as merged pattern name
     *
     * @param groups list of pattern groups to potentially merge
     * @return merged list with groups sharing counterpartyAccount combined
     */
    private List<PatternGroup> mergeGroupsByCounterpartyAccount(List<PatternGroup> groups) {
        // Separate groups with and without counterpartyAccount
        Map<AccountKey, List<PatternGroup>> groupsByAccount = new HashMap<>();
        List<PatternGroup> groupsWithoutAccount = new ArrayList<>();

        for (PatternGroup group : groups) {
            if (group.counterpartyAccount() != null && !group.counterpartyAccount().isBlank()) {
                AccountKey key = new AccountKey(group.counterpartyAccount(), group.type());
                groupsByAccount.computeIfAbsent(key, k -> new ArrayList<>()).add(group);
            } else {
                groupsWithoutAccount.add(group);
            }
        }

        // Merge groups that share the same counterpartyAccount
        List<PatternGroup> mergedGroups = new ArrayList<>(groupsWithoutAccount);

        for (Map.Entry<AccountKey, List<PatternGroup>> entry : groupsByAccount.entrySet()) {
            List<PatternGroup> accountGroups = entry.getValue();

            if (accountGroups.size() == 1) {
                // Only one group for this account - no merge needed
                mergedGroups.add(accountGroups.get(0));
            } else {
                // Multiple groups share same account - merge them
                PatternGroup merged = mergePatternGroups(accountGroups, entry.getKey());
                mergedGroups.add(merged);
            }
        }

        return mergedGroups;
    }

    /**
     * Key for grouping by counterpartyAccount.
     */
    private record AccountKey(String account, Type type) {
    }

    /**
     * Merges multiple pattern groups into one.
     */
    private PatternGroup mergePatternGroups(List<PatternGroup> groups, AccountKey key) {
        // Find common prefix of all patterns
        String mergedPattern = findCommonPrefix(
                groups.stream().map(PatternGroup::pattern).toList()
        );

        // If no common prefix found, use the shortest pattern
        if (mergedPattern.isEmpty() || mergedPattern.length() < 3) {
            mergedPattern = groups.stream()
                    .min(Comparator.comparingInt(g -> g.pattern().length()))
                    .map(PatternGroup::pattern)
                    .orElse("");
        }

        // Use sample from group with highest transaction count
        PatternGroup dominantGroup = groups.stream()
                .max(Comparator.comparingInt(PatternGroup::transactionCount))
                .orElse(groups.get(0));

        // Aggregate values
        int totalTransactionCount = groups.stream()
                .mapToInt(PatternGroup::transactionCount)
                .sum();

        BigDecimal totalAmount = groups.stream()
                .map(PatternGroup::totalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Combine all transaction IDs
        List<String> allTransactionIds = groups.stream()
                .flatMap(g -> g.transactionIds().stream())
                .toList();

        // Use most common bank category across all groups
        String mostCommonBankCategory = groups.stream()
                .filter(g -> g.bankCategory() != null && !g.bankCategory().isBlank())
                .collect(Collectors.groupingBy(
                        PatternGroup::bankCategory,
                        Collectors.summingInt(PatternGroup::transactionCount)
                ))
                .entrySet().stream()
                .max(Comparator.comparingInt(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse(dominantGroup.bankCategory());

        // Calculate weighted average merchant confidence
        double totalWeightedConfidence = groups.stream()
                .filter(g -> g.averageMerchantConfidence() != null)
                .mapToDouble(g -> g.averageMerchantConfidence() * g.transactionCount())
                .sum();
        int confidenceCount = groups.stream()
                .filter(g -> g.averageMerchantConfidence() != null)
                .mapToInt(PatternGroup::transactionCount)
                .sum();
        Double averageMerchantConfidence = confidenceCount > 0
                ? totalWeightedConfidence / confidenceCount
                : null;

        return new PatternGroup(
                mergedPattern,
                dominantGroup.sampleTransaction(),
                dominantGroup.sampleMerchant(),
                averageMerchantConfidence,
                dominantGroup.sampleDescription(),
                key.type(),
                totalTransactionCount,
                totalAmount,
                mostCommonBankCategory,
                allTransactionIds,
                key.account()
        );
    }

    /**
     * Finds the longest common prefix of multiple strings.
     */
    private String findCommonPrefix(List<String> strings) {
        if (strings == null || strings.isEmpty()) {
            return "";
        }
        if (strings.size() == 1) {
            return strings.get(0);
        }

        String first = strings.get(0);
        int prefixLength = first.length();

        for (int i = 1; i < strings.size(); i++) {
            String current = strings.get(i);
            prefixLength = Math.min(prefixLength, current.length());

            for (int j = 0; j < prefixLength; j++) {
                if (first.charAt(j) != current.charAt(j)) {
                    prefixLength = j;
                    break;
                }
            }
        }

        return first.substring(0, prefixLength).trim();
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
            String bankCategory,
            String merchant,
            Double merchantConfidence,
            String counterpartyAccount
    ) {
    }

    /**
     * A group of transactions with the same normalized pattern.
     */
    public record PatternGroup(
            String pattern,
            String sampleTransaction,
            String sampleMerchant,
            Double averageMerchantConfidence,
            String sampleDescription,
            Type type,
            int transactionCount,
            BigDecimal totalAmount,
            String bankCategory,
            List<String> transactionIds,
            String counterpartyAccount
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
