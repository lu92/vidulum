package com.multi.vidulum.bank_data_ingestion.domain;

import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.common.Money;

import java.time.ZonedDateTime;

/**
 * Original transaction data from the bank.
 *
 * @param bankTransactionId unique transaction ID from the bank (for deduplication)
 * @param name              transaction name from bank
 * @param description       additional description (nullable)
 * @param bankCategory      category from bank statement
 * @param money             transaction amount
 * @param type              INFLOW or OUTFLOW
 * @param paidDate          when the transaction was paid
 * @param merchant          extracted merchant name (from description for bank intermediary transactions)
 * @param merchantConfidence confidence score for merchant extraction (0.0 - 1.0)
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
        Double merchantConfidence
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
}
