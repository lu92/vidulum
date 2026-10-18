package com.multi.vidulum.cashflow.api;

import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Money;

import java.time.ZonedDateTime;

/**
 * A transaction that already happened, on its way into a cash flow.
 *
 * <p><b>Every field here is the type both ends already use</b>, and that is the entire point. The
 * port this replaces declared its own poorer shape - {@code double amount} plus
 * {@code String currency} instead of {@link Money}, {@code LocalDate} instead of
 * {@link ZonedDateTime} - so each transaction was degraded on the way out and guessed back on the
 * way in:
 *
 * <pre>
 * ingestion holds   Money(BigDecimal) · ZonedDateTime with a time and a zone
 * port DTO          double + String   · LocalDate                              &lt;- lost
 * HTTP client       MoneyDto(double)  · atStartOfDay(ZoneId.systemDefault())    &lt;- invented
 * endpoint DTO      Money             · ZonedDateTime
 * </pre>
 *
 * <p><b>What the date actually lost, measured.</b> The time of day, outright:
 * {@code 13:45:30+01:00} arrived as {@code 00:00} in whatever zone the server ran in, so the stored
 * instant depended on where the application was deployed - and for a transaction near a month
 * boundary that can move it into a different month, which decides both validation and the forecast.
 * {@code CashFlowSkeletonComponentTest} fails on exactly this when the old conversion is put back.
 *
 * <p><b>What the amount lost, also measured - and it is less than it looks.</b> Not the value:
 * {@code BigDecimal.valueOf(double)} goes through {@code Double.toString}, which yields the
 * shortest decimal that round-trips, so any amount up to about fifteen significant digits came back
 * numerically identical. What it lost is <b>scale</b> - {@code 1234.50} returned as {@code 1234.5}
 * and {@code 0.10} as {@code 0.1}, equal under {@code compareTo} but not under {@code equals}, and
 * stored with a different number of decimal places than the bank reported. Value loss needs more
 * than fifteen significant digits ({@code 12345678901234567.89} becomes
 * {@code 12345678901234568}), which no realistic balance reaches. So the honest statement is that
 * the amount was <i>reshaped</i> rather than corrupted - still no reason to do it, given both ends
 * hold {@link Money}.
 *
 * <p><b>{@code name} and {@code description} are plain strings</b> rather than cashflow's
 * {@code Name} and {@code Description}. Not an oversight: those two live in
 * {@code cashflow.domain}, which a consumer may not import, and ingestion holds strings anyway.
 * Wrapping happens on cashflow's side of the boundary.
 *
 * <p><b>{@code dueDate} is a known duplicate of {@code paidDate}.</b> A bank statement has no
 * notion of a payment term, and the only caller passes {@code paidDate} for both; the aggregate
 * stores it and nothing decides anything from it - for an imported cash change the forecast buckets
 * by {@code paidDate} explicitly, and the one query that filters on {@code dueDate} requires
 * {@code PENDING} status and a recurring-rule id, which an import has neither of. It is kept
 * because removing it is a separate decision that was deliberately deferred, not because it carries
 * information.
 */
public record HistoricalCashChange(
        CategoryName categoryName,
        String name,
        String description,
        Money money,
        Type type,
        ZonedDateTime dueDate,
        ZonedDateTime paidDate,
        boolean selfTransfer) {
}
