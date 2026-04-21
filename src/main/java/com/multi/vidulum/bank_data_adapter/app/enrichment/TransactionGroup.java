package com.multi.vidulum.bank_data_adapter.app.enrichment;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a group of transactions with the same name (exact match).
 * Used to reduce AI calls by enriching only unique transaction patterns.
 */
@Data
@Builder
public class TransactionGroup {

    /**
     * Normalized group key (name.toUpperCase().trim()).
     */
    private String groupKey;

    /**
     * Representative transaction to send to AI.
     * Selected based on best available bankCategory.
     */
    private TransactionForEnrichment representative;

    /**
     * All row indexes belonging to this group.
     */
    @Builder.Default
    private List<Integer> rowIndexes = new ArrayList<>();

    /**
     * Original bankCategory values for each row (for selective propagation).
     * Maps rowIndex -> original bankCategory.
     */
    @Builder.Default
    private java.util.Map<Integer, String> originalBankCategories = new java.util.HashMap<>();

    /**
     * Add a transaction to this group.
     */
    public void addTransaction(TransactionForEnrichment txn) {
        rowIndexes.add(txn.getRowIndex());
        originalBankCategories.put(txn.getRowIndex(), txn.getBankCategory());

        // Update representative if this one has better bankCategory
        if (representative == null) {
            representative = txn;
        } else if (isBetterBankCategory(txn.getBankCategory(), representative.getBankCategory())) {
            // Keep the rowIndex of the first transaction, but use better bankCategory
            representative = TransactionForEnrichment.builder()
                    .rowIndex(representative.getRowIndex())
                    .name(representative.getName())
                    .description(representative.getDescription())
                    .bankCategory(txn.getBankCategory())
                    .build();
        }
    }

    /**
     * Check if category1 is better than category2.
     * Priority: specific category > "Inne" > empty
     */
    private boolean isBetterBankCategory(String category1, String category2) {
        int score1 = scoreBankCategory(category1);
        int score2 = scoreBankCategory(category2);
        return score1 > score2;
    }

    private int scoreBankCategory(String category) {
        if (category == null || category.isBlank()) {
            return 0; // Empty - worst
        }
        if ("Inne".equalsIgnoreCase(category)) {
            return 1; // "Inne" - better than empty
        }
        return 2; // Specific category - best
    }

    /**
     * Get the number of transactions in this group.
     */
    public int size() {
        return rowIndexes.size();
    }

    /**
     * Check if original bankCategory was empty for given rowIndex.
     */
    public boolean hadEmptyBankCategory(int rowIndex) {
        String original = originalBankCategories.get(rowIndex);
        return original == null || original.isBlank();
    }
}
