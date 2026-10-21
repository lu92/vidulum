package com.multi.vidulum.recurring_rules.domain;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * How far ahead a rule predicts: from today to the end of the twelfth month.
 *
 * <p><b>A value, not a number copied twice.</b> {@code RecurringRuleExecutor} generates cash changes
 * for this window and the delete-impact preview says what deleting the rule would remove from it -
 * so the two must mean the same window, or the preview reports occurrences that were never
 * generated (or misses ones that were). Both held their own {@code FORECAST_MONTHS = 12} and their
 * own copy of the arithmetic, which is the duplication that diverges silently: nothing fails, the
 * preview just stops telling the truth.
 *
 * <p>{@code from} is today rather than the first of the month on purpose - a rule does not predict
 * backwards, and an occurrence already in the past is already in the cash flow.
 */
public record ForecastWindow(LocalDate from, LocalDate to) {

    private static final int MONTHS = 12;

    public static ForecastWindow startingAt(LocalDate today) {
        return new ForecastWindow(today, YearMonth.from(today).plusMonths(MONTHS - 1).atEndOfMonth());
    }

    /**
     * How many months the window spans, counting the current one.
     */
    public static int months() {
        return MONTHS;
    }
}
