package com.multi.vidulum.cashflow.api.view;

import com.multi.vidulum.cashflow.api.error.ImportDateBeforeStartPeriodException;
import com.multi.vidulum.cashflow.api.error.ImportDateInFutureException;
import com.multi.vidulum.cashflow.api.error.ImportDateOutsideSetupPeriodException;
import com.multi.vidulum.cashflow.api.error.ImportNotAllowedInClosedModeException;
import com.multi.vidulum.cashflow.api.error.ImportToForecastedMonthNotAllowedException;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.time.ZonedDateTime;

import static com.multi.vidulum.cashflow.api.view.CashFlowSkeletonView.LifecyclePhase.CLOSED;
import static com.multi.vidulum.cashflow.api.view.CashFlowSkeletonView.LifecyclePhase.OPEN;
import static com.multi.vidulum.cashflow.api.view.CashFlowSkeletonView.LifecyclePhase.SETUP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The import rule, now that there is one of it.
 *
 * <p>These assertions used to be impossible to write cheaply. The rule lived twice - as five
 * exceptions inside {@code ImportHistoricalCashChangeCommandHandler}, reachable only by loading an
 * aggregate, and as five {@code String.format} messages inside
 * {@code StageTransactionsCommandHandler}, reachable only through a staging session. Neither copy
 * had a unit test; both were exercised incidentally through integration tests that needed Mongo and
 * Kafka to ask what a date means.
 *
 * <p><b>Precedence is pinned here on purpose.</b> A date can break two conditions at once, and
 * which refusal surfaces decides the {@code ErrorCode} an endpoint answers with.
 * {@code CashFlowErrorHandlingTest} relies on that order to the point of explaining, in a comment,
 * why it cannot test {@code IMPORT_DATE_IN_FUTURE} in SETUP mode at all - the setup-period refusal
 * always wins. If someone reorders the checks, this file says so before that comment becomes a lie.
 */
class ImportWindowTest {

    private static final CashFlowId CASH_FLOW_ID = CashFlowId.of("CF10000001");
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2022-01-15T12:00:00Z[UTC]");

    private static ImportWindow window(CashFlowSkeletonView.LifecyclePhase phase,
                                       YearMonth startPeriod,
                                       YearMonth activePeriod) {
        return new ImportWindow(CASH_FLOW_ID, phase, activePeriod, startPeriod);
    }

    private static ZonedDateTime at(String isoDate) {
        return ZonedDateTime.parse(isoDate + "T10:00:00Z[UTC]");
    }

    @Test
    void inSetupAcceptsAMonthBeforeTheActiveOne() {
        ImportWindow window = window(SETUP, YearMonth.of(2021, 10), YearMonth.of(2022, 1));

        assertThat(window.accepts(at("2021-11-20"), NOW)).isTrue();
        assertThat(window.refusalFor(at("2021-11-20"), NOW)).isEmpty();
    }

    @Test
    void inSetupRefusesTheActiveMonthItself() {
        ImportWindow window = window(SETUP, YearMonth.of(2021, 10), YearMonth.of(2022, 1));

        assertThat(window.refusalFor(at("2022-01-05"), NOW))
                .get()
                .isInstanceOf(ImportDateOutsideSetupPeriodException.class)
                .extracting(e -> e.getErrorCode())
                .isEqualTo(ErrorCode.IMPORT_DATE_OUTSIDE_SETUP_PERIOD);
    }

    @Test
    void inOpenAcceptsTheActiveMonthAndEverythingBeforeIt() {
        ImportWindow window = window(OPEN, YearMonth.of(2021, 10), YearMonth.of(2022, 1));

        assertThat(window.accepts(at("2022-01-05"), NOW)).as("the active month").isTrue();
        assertThat(window.accepts(at("2021-12-31"), NOW)).as("a rolled-over month").isTrue();
    }

    @Test
    void inOpenRefusesAMonthAfterTheActiveOne() {
        ImportWindow window = window(OPEN, YearMonth.of(2021, 10), YearMonth.of(2022, 1));

        // Note it is not "in the future" relative to now - it is a forecasted month, which is a
        // different refusal with a different code, and the distinction only exists because the
        // phase is checked before the clock.
        assertThat(window.refusalFor(at("2022-02-10"), ZonedDateTime.parse("2022-03-01T00:00:00Z[UTC]")))
                .get()
                .isInstanceOf(ImportToForecastedMonthNotAllowedException.class)
                .extracting(e -> e.getErrorCode())
                .isEqualTo(ErrorCode.IMPORT_TO_FORECASTED_MONTH_NOT_ALLOWED);
    }

    @Test
    void closedRefusesEverything() {
        ImportWindow window = window(CLOSED, YearMonth.of(2021, 10), YearMonth.of(2022, 1));

        assertThat(window.refusalFor(at("2021-11-20"), NOW))
                .as("a date that SETUP and OPEN would both have accepted")
                .get()
                .isInstanceOf(ImportNotAllowedInClosedModeException.class)
                .extracting(e -> e.getErrorCode())
                .isEqualTo(ErrorCode.CASHFLOW_CLOSED);
    }

    @Test
    void refusesADateBeforeTheCashFlowExisted() {
        ImportWindow window = window(SETUP, YearMonth.of(2021, 10), YearMonth.of(2022, 1));

        assertThat(window.refusalFor(at("2021-09-30"), NOW))
                .get()
                .isInstanceOf(ImportDateBeforeStartPeriodException.class)
                .extracting(e -> e.getErrorCode())
                .isEqualTo(ErrorCode.IMPORT_DATE_BEFORE_START);
    }

    @Test
    void refusesADateThatHasNotHappenedYet() {
        // OPEN with an active period containing "now", so the phase check passes and the clock is
        // what refuses: the only arrangement in which IMPORT_DATE_IN_FUTURE is reachable at all.
        ImportWindow window = window(OPEN, YearMonth.of(2021, 10), YearMonth.of(2022, 1));

        assertThat(window.refusalFor(at("2022-01-20"), NOW))
                .as("same month as now, but four days ahead of it")
                .get()
                .isInstanceOf(ImportDateInFutureException.class)
                .extracting(e -> e.getErrorCode())
                .isEqualTo(ErrorCode.IMPORT_DATE_IN_FUTURE);
    }

    @Test
    void thePhaseRefusalWinsOverTheFutureOne() {
        ImportWindow window = window(SETUP, YearMonth.of(2021, 10), YearMonth.of(2022, 1));
        ZonedDateTime bothWrong = at("2022-02-20");   // after activePeriod AND after now

        assertThat(window.refusalFor(bothWrong, NOW))
                .as("the order the aggregate used, which CashFlowErrorHandlingTest depends on")
                .get()
                .isInstanceOf(ImportDateOutsideSetupPeriodException.class);
    }

    @Test
    void thePhaseRefusalWinsOverTheStartPeriodOne() {
        // CLOSED plus a date before startPeriod: two refusals, and the phase is the one reported,
        // because a closed cash flow has nothing to say about periods.
        ImportWindow window = window(CLOSED, YearMonth.of(2021, 10), YearMonth.of(2022, 1));

        assertThat(window.refusalFor(at("2020-01-01"), NOW))
                .get()
                .isInstanceOf(ImportNotAllowedInClosedModeException.class);
    }

    @Test
    void theWindowIsTheSkeletonsOwnThreeFields() {
        CashFlowSkeletonView skeleton = new CashFlowSkeletonView(
                CASH_FLOW_ID,
                com.multi.vidulum.common.UserId.of("U10000001"),
                SETUP,
                YearMonth.of(2022, 1),
                YearMonth.of(2021, 10),
                java.util.List.of(),
                java.util.List.of());

        assertThat(skeleton.importWindow())
                .as("no extra fetch - the consumer already has everything the rule needs")
                .isEqualTo(new ImportWindow(CASH_FLOW_ID, SETUP, YearMonth.of(2022, 1), YearMonth.of(2021, 10)));
    }
}
