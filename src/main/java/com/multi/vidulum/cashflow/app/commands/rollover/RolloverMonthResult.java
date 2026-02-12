package com.multi.vidulum.cashflow.app.commands.rollover;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.common.Money;

import java.time.YearMonth;

/**
 * Result of a successful month rollover operation.
 *
 * @param cashFlowId       the CashFlow that was rolled over
 * @param rolledOverPeriod the period that was rolled over (now has ROLLED_OVER status)
 * @param newActivePeriod  the new active period (now has ACTIVE status)
 * @param closingBalance   the balance at the end of the rolled over period
 */
public record RolloverMonthResult(
        CashFlowId cashFlowId,
        YearMonth rolledOverPeriod,
        YearMonth newActivePeriod,
        Money closingBalance
) {
}
