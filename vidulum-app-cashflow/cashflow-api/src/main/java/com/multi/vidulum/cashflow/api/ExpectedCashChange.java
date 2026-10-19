package com.multi.vidulum.cashflow.api;

import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Money;

import java.time.ZonedDateTime;

/**
 * One transaction a consumer expects to happen, as it crosses the contract.
 *
 * <p>{@code name} and {@code description} are strings for the same reason they are on
 * {@link HistoricalCashChange}: the domain's {@code Name} and {@code Description} live in
 * {@code cashflow.domain}, where a consumer may not look. The implementation wraps them.
 *
 * <p>No id: {@link CashFlowModuleApi#appendExpected} answers with the ids it minted, and it mints
 * them for the whole batch in one go.
 */
public record ExpectedCashChange(
        CategoryName categoryName,
        String name,
        String description,
        Money money,
        Type type,
        ZonedDateTime dueDate) {
}
