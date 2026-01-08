package com.multi.vidulum.bank_data_ingestion.domain;

import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.common.Money;

import java.time.ZonedDateTime;

/**
 * Transaction data after applying category mappings.
 *
 * @param name               transaction name
 * @param description        additional description (nullable)
 * @param categoryName       target category name in the system
 * @param parentCategoryName parent category name if subcategory (nullable)
 * @param money              transaction amount
 * @param type               INFLOW or OUTFLOW
 * @param paidDate           when the transaction was paid
 */
public record MappedTransactionData(
        String name,
        String description,
        CategoryName categoryName,
        CategoryName parentCategoryName,
        Money money,
        Type type,
        ZonedDateTime paidDate
) {
}
