package com.multi.vidulum.bank_data_adapter.domain;

/**
 * Classification of transaction type determined by AI enrichment.
 *
 * This classification helps:
 * 1. Determine if merchant extraction makes sense
 * 2. Enable auto-categorization for non-merchant transactions
 * 3. Improve AI categorization by filtering out non-categorizable transactions
 */
public enum TransactionClassification {

    /**
     * Payment to a merchant/business/person.
     * Merchant extraction is meaningful.
     * Should be categorized by AI categorization.
     *
     * Examples:
     * - "ŻABKA POLSKA 4521 WARSZAWA" → merchant: ŻABKA
     * - "NETFLIX.COM" → merchant: NETFLIX
     * - "Jan Kowalski" (personal transfer) → merchant: JAN KOWALSKI
     */
    MERCHANT,

    /**
     * Bank fee, commission, or service charge.
     * No meaningful merchant (the bank itself is not a merchant).
     * Auto-category: "Bank fees" / "Opłaty bankowe"
     *
     * Examples:
     * - "Prowizja za przelew natychmiastowy wychodzący KIR"
     * - "Opłata za obsługę karty MasterCard"
     * - "Monthly account fee"
     * - "Wire transfer commission"
     */
    BANK_FEE,

    /**
     * Cash withdrawal from ATM or bank branch.
     * No merchant (ATM terminal is not a merchant).
     * Auto-category: "Cash" / "Gotówka"
     *
     * Examples:
     * - "00146 2703W250H WARSZAWA" (ATM terminal code)
     * - "Wypłata z bankomatu"
     * - "ATM withdrawal"
     * - "EURONET 12345"
     */
    CASH_WITHDRAWAL,

    /**
     * Cash deposit at ATM or bank branch.
     * No merchant.
     * Auto-category: "Cash" / "Gotówka"
     *
     * Examples:
     * - "Wpłata gotówkowa"
     * - "Cash deposit"
     * - "Wpłata we wpłatomacie"
     */
    CASH_DEPOSIT,

    /**
     * Transfer between own accounts (same owner).
     * No merchant (self-transfer).
     * Auto-category: "Internal transfer" / "Przelew wewnętrzny"
     * Should be excluded from budget calculations.
     *
     * Examples:
     * - Transfer from checking to savings
     * - "Przelew własny"
     * - Same name appears as sender and in account owner
     */
    SELF_TRANSFER,

    /**
     * Interest payment (credit or debit).
     * No merchant.
     * Auto-category: "Interest" / "Odsetki"
     *
     * Examples:
     * - "Odsetki od lokaty"
     * - "Interest payment"
     * - "Kapitalizacja odsetek"
     */
    INTEREST,

    /**
     * Cannot determine transaction type.
     * Fallback - try to extract merchant anyway.
     * Should be categorized by AI categorization.
     *
     * Examples:
     * - Ambiguous transaction descriptions
     * - Unknown patterns
     */
    UNKNOWN;

    /**
     * Whether this classification type has a meaningful merchant.
     * Only MERCHANT and UNKNOWN should have merchant extracted.
     */
    public boolean hasMerchant() {
        return this == MERCHANT || this == UNKNOWN;
    }

    /**
     * Whether this classification should be auto-categorized
     * (skipped in AI categorization phase).
     * These transactions already have bankCategory from enrichment.
     */
    public boolean isAutoCategorizeable() {
        return this == BANK_FEE ||
               this == CASH_WITHDRAWAL ||
               this == CASH_DEPOSIT ||
               this == SELF_TRANSFER ||
               this == INTEREST;
    }

    /**
     * Whether this classification should be included in budget/expense analysis.
     * Self-transfers should be excluded as they don't represent real income/expense.
     */
    public boolean includeInBudget() {
        return this != SELF_TRANSFER;
    }

    /**
     * Parse classification from string (case-insensitive).
     * Returns UNKNOWN for null or unrecognized values.
     */
    public static TransactionClassification fromString(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
