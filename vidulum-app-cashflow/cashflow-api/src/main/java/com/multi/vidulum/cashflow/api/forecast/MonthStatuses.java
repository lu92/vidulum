package com.multi.vidulum.cashflow.api.forecast;

import com.multi.vidulum.common.CashFlowId;

import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;

/**
 * Every month the forecast knows about, and the rule that decides which of them accept an import.
 *
 * <p><b>A value, not a predicate on the contract.</b> The rule is asked once per row of a bank
 * statement, and the test file in {@code docs/manual-testing} has 402 of them - so
 * {@code acceptsImportAt(month)} as a contract method would be 402 requests for one upload. The
 * consumer fetches this once and evaluates locally, against the producer's logic rather than a copy
 * of it. {@link com.multi.vidulum.cashflow.api.view.ImportWindow} is the same arrangement for the
 * aggregate's own date rule, and for the same reason.
 *
 * <p>The two are also deliberately asked in that order. {@code ImportWindow} is the <i>necessary</i>
 * condition - the lifecycle phase and both period boundaries - and it is cashflow's own rule, the
 * one the write would enforce. This one is stricter and belongs to the read model, so staging only
 * consults it once the window has already accepted the date. That way a single row is never
 * reported twice for one thing being wrong with its date.
 */
public record MonthStatuses(CashFlowId cashFlowId, Map<YearMonth, MonthStatus> byMonth) {

    public MonthStatuses {
        byMonth = byMonth == null ? Map.of() : Map.copyOf(byMonth);
    }

    /**
     * No forecast exists yet for this cash flow.
     *
     * <p>Not an error, and not the same as "this month is refused": the read model is built from
     * events, so a cash flow created moments ago legitimately has no statement. Every month is
     * absent, which {@link #acceptsImportAt} refuses - the same answer the HTTP client has always
     * given when the forecast could not be reached.
     */
    public static MonthStatuses none(CashFlowId cashFlowId) {
        return new MonthStatuses(cashFlowId, Map.of());
    }

    /**
     * The status of one month, or empty when that month is outside the forecast range.
     */
    public Optional<MonthStatus> at(YearMonth month) {
        return Optional.ofNullable(byMonth.get(month));
    }

    /**
     * Whether a transaction paid in this month may be imported.
     *
     * <p>Allowed where the month is real and still writable: {@link MonthStatus#IMPORT_PENDING}
     * during historical backfill, {@link MonthStatus#IMPORTED} and
     * {@link MonthStatus#ROLLED_OVER} for gap filling, {@link MonthStatus#ACTIVE} for ongoing
     * sync. Refused for {@link MonthStatus#FORECASTED}, which has not happened yet, and for
     * {@link MonthStatus#ATTESTED}, which is read-only.
     *
     * <p>A month the forecast does not know is refused rather than assumed: the alternative would
     * let a row land in a month no statement accounts for.
     */
    public boolean acceptsImportAt(YearMonth month) {
        return at(month)
                .map(status -> switch (status) {
                    case IMPORT_PENDING, IMPORTED, ROLLED_OVER, ACTIVE -> true;
                    case FORECASTED, ATTESTED -> false;
                })
                .orElse(false);
    }

    public boolean isEmpty() {
        return byMonth.isEmpty();
    }
}
