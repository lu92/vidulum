package com.multi.vidulum.bank_data_ingestion.domain;

import com.multi.vidulum.bank_data_adapter.domain.TransactionClassification;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.common.Money;

import java.time.ZonedDateTime;

/**
 * Original transaction data from the bank.
 *
 * @param bankTransactionId unique transaction ID from the bank (for deduplication)
 * @param name              transaction name from bank
 * @param description       additional description (nullable)
 * @param bankCategory      category from bank statement (WHAT was purchased)
 * @param money             transaction amount
 * @param type              INFLOW or OUTFLOW
 * @param paidDate          when the transaction was paid
 * @param merchant          extracted merchant name (from description for bank intermediary transactions)
 * @param merchantConfidence confidence score for merchant extraction (0.0 - 1.0)
 * @param counterpartyAccount the other party's bank account number (for OUTFLOW: recipient, for INFLOW: sender)
 * @param paymentMethod     payment method used (HOW payment was made: CARD, TRANSFER, BLIK, etc.)
 * @param classification    transaction classification (MERCHANT, BANK_FEE, CASH_WITHDRAWAL, etc.)
 */
public record OriginalTransactionData(
        String bankTransactionId,
        String name,
        String description,
        String bankCategory,
        Money money,
        Type type,
        ZonedDateTime paidDate,
        String merchant,
        Double merchantConfidence,
        String counterpartyAccount,
        PaymentMethod paymentMethod,
        TransactionClassification classification
) {
    /**
     * Returns effective merchant (name if merchant is null).
     * This is used for pattern grouping in PatternDeduplicator.
     */
    public String effectiveMerchant() {
        return merchant != null && !merchant.isBlank() ? merchant : name;
    }

    /**
     * Returns true if merchant was extracted with high confidence (>= 0.7).
     */
    public boolean hasHighConfidenceMerchant() {
        return merchant != null && !merchant.isBlank()
                && merchantConfidence != null && merchantConfidence >= 0.7;
    }

    /**
     * Returns effective payment method (OTHER if null).
     */
    public PaymentMethod effectivePaymentMethod() {
        return paymentMethod != null ? paymentMethod : PaymentMethod.OTHER;
    }

    /**
     * Returns effective classification (UNKNOWN if null).
     */
    public TransactionClassification effectiveClassification() {
        return classification != null ? classification : TransactionClassification.UNKNOWN;
    }

    /**
     * Returns true if this transaction should be auto-categorized.
     * Auto-categorizable transactions (BANK_FEE, CASH_WITHDRAWAL, etc.) already have
     * their bankCategory from enrichment and should skip AI categorization.
     */
    public boolean isAutoCategorizeable() {
        return effectiveClassification().isAutoCategorizeable();
    }

    /**
     * Returns true if this transaction should be included in budget analysis.
     * Self-transfers should be excluded as they don't represent real income/expense.
     */
    public boolean includeInBudget() {
        return effectiveClassification().includeInBudget();
    }

    /**
     * Returns true if this transaction has a meaningful merchant.
     * Non-merchant transactions (BANK_FEE, CASH_WITHDRAWAL, etc.) have null merchant.
     */
    public boolean hasMerchant() {
        return effectiveClassification().hasMerchant() && merchant != null && !merchant.isBlank();
    }
}
