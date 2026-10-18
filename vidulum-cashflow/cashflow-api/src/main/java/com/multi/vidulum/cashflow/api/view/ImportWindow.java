package com.multi.vidulum.cashflow.api.view;

import com.multi.vidulum.cashflow.api.error.ImportDateBeforeStartPeriodException;
import com.multi.vidulum.cashflow.api.error.ImportDateInFutureException;
import com.multi.vidulum.cashflow.api.error.ImportDateOutsideSetupPeriodException;
import com.multi.vidulum.cashflow.api.error.ImportNotAllowedInClosedModeException;
import com.multi.vidulum.cashflow.api.error.ImportToForecastedMonthNotAllowedException;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.error.BusinessException;

import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Which dates a cash flow will accept an import for.
 *
 * <p><b>Why this is a type rather than a method on the contract.</b> The rule used to exist twice.
 * {@code ImportHistoricalCashChangeCommandHandler} enforced it as five typed exceptions at write
 * time; {@code StageTransactionsCommandHandler} reimplemented the same five conditions as
 * {@code String.format} messages at staging time, reading {@code activePeriod} and
 * {@code startPeriod} off its own DTO. Two copies of one rule, in two representations - and they
 * had already diverged.
 *
 * <p>Making it a method on {@code CashFlowModuleApi} would not have fixed that: a predicate asked
 * per row is a request per row, and a 402-line bank statement would pay 402 of them. Making it a
 * <b>value</b> means the rule travels with the data it needs. The consumer fetches
 * {@link CashFlowSkeletonView#importWindow()} once and evaluates 402 rows locally, against the
 * producer's own logic rather than a copy of it.
 *
 * <p><b>What this does not cover, deliberately.</b> Only the conditions the aggregate itself
 * enforces, which are exactly the ones that can make a write fail. Staging layers an extra,
 * stricter check on top - a month that is {@code FORECASTED} or {@code ATTESTED} per the forecast
 * module is refused there - and that check stays in staging, because month statuses belong to
 * {@code cashflow_forecast_processor} and cashflow does not read them. This window is therefore the
 * <i>necessary</i> condition, not the whole policy; passing it means the aggregate will not refuse
 * on date grounds.
 */
public record ImportWindow(CashFlowId cashFlowId,
                           CashFlowSkeletonView.LifecyclePhase phase,
                           YearMonth activePeriod,
                           YearMonth startPeriod) {

    /**
     * Why this cash flow would refuse an import at {@code paidDate}, or empty if it would accept.
     *
     * <p>Returns the failure rather than throwing it, because the two callers want different things
     * from it: the command handler throws it, and staging records its {@code ErrorCode} against the
     * row. Both get the same typed object with the same message and the same HTTP status - which is
     * the part the duplicated version could not give, having only free text.
     *
     * <p><b>The order is the aggregate's, to the letter, and that is not cosmetic.</b> When a date
     * violates two conditions at once the caller sees whichever is checked first, so reordering
     * would change the error code an endpoint answers with. The phase check comes before the period
     * boundaries, and the future check comes last - which is why a {@code paidDate} that is both in
     * the future and at or after {@code activePeriod} reports
     * {@code IMPORT_DATE_OUTSIDE_SETUP_PERIOD} rather than {@code IMPORT_DATE_IN_FUTURE}.
     * {@code CashFlowErrorHandlingTest} documents exactly that precedence in a comment explaining
     * why it cannot test the future case in SETUP mode at all.
     */
    public Optional<BusinessException> refusalFor(ZonedDateTime paidDate, ZonedDateTime now) {
        YearMonth target = YearMonth.from(paidDate);

        Optional<BusinessException> phaseRefusal = switch (phase) {
            case SETUP -> target.isBefore(activePeriod)
                    ? Optional.<BusinessException>empty()
                    : Optional.<BusinessException>of(
                            new ImportDateOutsideSetupPeriodException(paidDate, target, activePeriod));
            case OPEN -> target.isAfter(activePeriod)
                    ? Optional.<BusinessException>of(
                            new ImportToForecastedMonthNotAllowedException(cashFlowId, target, activePeriod))
                    : Optional.<BusinessException>empty();
            case CLOSED -> Optional.<BusinessException>of(
                    new ImportNotAllowedInClosedModeException(cashFlowId));
        };
        if (phaseRefusal.isPresent()) {
            return phaseRefusal;
        }

        if (target.isBefore(startPeriod)) {
            return Optional.of(new ImportDateBeforeStartPeriodException(paidDate, target, startPeriod));
        }
        if (paidDate.isAfter(now)) {
            return Optional.of(new ImportDateInFutureException(paidDate, now));
        }
        return Optional.empty();
    }

    /**
     * Whether an import at {@code paidDate} would be accepted - the same question as
     * {@link #refusalFor}, for callers that only need the verdict.
     */
    public boolean accepts(ZonedDateTime paidDate, ZonedDateTime now) {
        return refusalFor(paidDate, now).isEmpty();
    }
}
