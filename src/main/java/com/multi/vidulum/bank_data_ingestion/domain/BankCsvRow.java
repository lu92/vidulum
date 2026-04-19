package com.multi.vidulum.bank_data_ingestion.domain;

import com.multi.vidulum.cashflow.domain.Type;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Represents a single row from bank CSV export.
 * Normalized format - AI maps various bank exports to this structure.
 */
public record BankCsvRow(

        /**
         * Unique transaction ID from bank.
         * OPTIONAL - if null, generated as hash(operationDate + amount + name)
         */
        String bankTransactionId,

        /**3
         * Transaction name/title.
         * REQUIRED
         */
        String name,

        /**
         * Full transaction description from bank.
         * OPTIONAL - if null, empty string
         */
        String description,

        /**
         * Category assigned by bank (e.g. "Zakupy kartą", "Przelew").
         * OPTIONAL - if null, set to "Uncategorized"
         */
        String bankCategory,

        /**
         * Transaction amount (always positive).
         * REQUIRED
         */
        BigDecimal amount,

        /**
         * Currency code (ISO 4217).
         * REQUIRED
         */
        String currency,

        /**
         * Transaction type (INFLOW or OUTFLOW).
         * REQUIRED
         */
        Type type,

        /**
         * Operation date - when transaction was made.
         * REQUIRED
         */
        LocalDate operationDate,

        /**
         * Booking date - when transaction was posted to account.
         * OPTIONAL - if null, defaults to operationDate
         */
        LocalDate bookingDate,

        /**
         * Source account number (IBAN format).
         * OPTIONAL - for OUTFLOW: user's account, for INFLOW: sender's account
         */
        String sourceAccountNumber,

        /**
         * Target account number (IBAN format).
         * OPTIONAL - for OUTFLOW: recipient's account, for INFLOW: user's account
         */
        String targetAccountNumber,

        /**
         * Extracted merchant name (from description for bank intermediary transactions).
         * OPTIONAL - AI extracts this when name is "BANK PEKAO S.A." etc.
         * Examples: "BADOO", "NETFLIX", "OPENAI", "CLAUDE"
         */
        String merchant,

        /**
         * Confidence score for merchant extraction (0.0 - 1.0).
         * OPTIONAL - null if merchant was not extracted by AI.
         * Higher values indicate more reliable extraction.
         */
        Double merchantConfidence,

        /**
         * Payment method used for transaction (HOW payment was made).
         * OPTIONAL - null defaults to OTHER.
         * This is separate from bankCategory which describes WHAT was purchased.
         * Examples: CARD, TRANSFER, BLIK, DIRECT_DEBIT
         */
        PaymentMethod paymentMethod

) {
    /**
     * Returns effective booking date (operationDate if bookingDate is null).
     */
    public LocalDate effectiveBookingDate() {
        return bookingDate != null ? bookingDate : operationDate;
    }

    /**
     * Returns effective description (empty string if null).
     */
    public String effectiveDescription() {
        return description != null ? description : "";
    }

    /**
     * Returns effective bank category ("Uncategorized" if null).
     */
    public String effectiveBankCategory() {
        return bankCategory != null && !bankCategory.isBlank() ? bankCategory : "Uncategorized";
    }

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
     * Returns the counterparty account number (the other party's bank account).
     * AI may put the account in either column, so we check both:
     * - First, we try the semantically correct column based on type
     * - If that's empty, we fall back to the other column
     * For OUTFLOW: prefer targetAccountNumber (recipient), fallback to sourceAccountNumber
     * For INFLOW: prefer sourceAccountNumber (sender), fallback to targetAccountNumber
     */
    public String counterpartyAccount() {
        if (type == Type.OUTFLOW) {
            // OUTFLOW: recipient's account should be in targetAccountNumber
            if (targetAccountNumber != null && !targetAccountNumber.isBlank()) {
                return targetAccountNumber;
            }
            // Fallback: AI might have put it in sourceAccountNumber
            return sourceAccountNumber;
        } else {
            // INFLOW: sender's account should be in sourceAccountNumber
            if (sourceAccountNumber != null && !sourceAccountNumber.isBlank()) {
                return sourceAccountNumber;
            }
            // Fallback: AI might have put it in targetAccountNumber
            return targetAccountNumber;
        }
    }
}
