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

        /**
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
        String targetAccountNumber

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
}
