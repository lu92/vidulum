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
 */
public record OriginalTransactionData(
        String bankTransactionId,
        String name,
        String description,
        String bankCategory,
        Money money,
        Type type,
        ZonedDateTime paidDate
) {
}
