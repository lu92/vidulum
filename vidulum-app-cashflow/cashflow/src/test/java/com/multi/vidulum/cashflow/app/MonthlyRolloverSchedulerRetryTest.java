package com.multi.vidulum.cashflow.app;

import com.multi.vidulum.cashflow.app.commands.rollover.RolloverMonthCommand;
import com.multi.vidulum.cashflow.app.commands.rollover.RolloverMonthResult;
import com.multi.vidulum.cashflow.domain.BankAccount;
import com.multi.vidulum.cashflow.domain.CashFlow;
import com.multi.vidulum.cashflow.domain.Description;
import com.multi.vidulum.cashflow.domain.DomainCashFlowRepository;
import com.multi.vidulum.cashflow.domain.Name;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.Checksum;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The second half of F57 on the rollover side: a conflict must cost a retry, not the month.
 *
 * <p>With a version on the cash flow, an owner writing at 02:00 makes the job's save fail. Dropping
 * the cash flow there would mean its month does not roll over at all until the next run - and the
 * forecast depends on the active period, so the owner would be looking at a stale month for a day.
 * The command is sent again, and because its handler re-reads the aggregate, the retry rolls over
 * from whatever that owner's write left behind.
 */
class MonthlyRolloverSchedulerRetryTest {

    private static final CashFlowId CASH_FLOW_ID = CashFlowId.of("CF10000001");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-03-01T02:00:00Z"), ZoneOffset.UTC);

    private final DomainCashFlowRepository cashFlows = mock(DomainCashFlowRepository.class);
    private final CommandGateway commandGateway = mock(CommandGateway.class);

    private final MonthlyRolloverScheduler scheduler =
            new MonthlyRolloverScheduler(cashFlows, commandGateway, FIXED_CLOCK);

    @Test
    void aConflictIsRetriedRatherThanCostingTheCashFlowItsRollover() {
        oneCashFlowBehindBy(YearMonth.of(2022, 2));
        when(commandGateway.send(any(RolloverMonthCommand.class)))
                .thenThrow(new OptimisticLockingFailureException("owner wrote first"))
                .thenReturn(new RolloverMonthResult(CASH_FLOW_ID, YearMonth.of(2022, 2), YearMonth.of(2022, 3),
                        com.multi.vidulum.common.Money.of(0, "PLN")));

        scheduler.performMonthlyRollover();

        verify(commandGateway, times(2)).send(any(RolloverMonthCommand.class));
    }

    @Test
    void aConflictThatNeverClearsIsOnlyThisCashFlowsProblem() {
        oneCashFlowBehindBy(YearMonth.of(2022, 2));
        when(commandGateway.send(any(RolloverMonthCommand.class)))
                .thenThrow(new OptimisticLockingFailureException("owner keeps writing"));

        assertThatCode(scheduler::performMonthlyRollover)
                .as("the night's work carries on; the cash flow is picked up by the next run")
                .doesNotThrowAnyException();

        verify(commandGateway, times(3))
                .send(any(RolloverMonthCommand.class));
    }

    @Test
    void aQuietMonthSendsNothing() {
        when(cashFlows.findOpenCashFlowsNeedingRollover(any())).thenReturn(List.of());

        scheduler.performMonthlyRollover();

        verify(commandGateway, times(0)).send(any(RolloverMonthCommand.class));
    }

    /**
     * A real aggregate rather than a mock: {@code CashFlowSnapshot} is a record, so its accessors
     * are final and cannot be stubbed - and a snapshot built for real also proves the scheduler
     * reads the period it claims to.
     */
    private void oneCashFlowBehindBy(YearMonth activePeriod) {
        CashFlow behind = CashFlow.from(new CashFlowSnapshot(
                CASH_FLOW_ID,
                UserId.of("U10000001"),
                new Name("Nest Bank"),
                new Description("current account"),
                BankAccount.fromIban("Nest Bank", "PL61109010140000071219812874",
                        Currency.of("PLN"), Money.of(0, "PLN"), null),
                CashFlow.CashFlowStatus.OPEN,
                Map.of(),
                activePeriod,
                activePeriod,
                Money.of(0, "PLN"),
                List.of(),
                List.of(),
                ZonedDateTime.parse("2022-01-01T00:00:00Z"),
                ZonedDateTime.parse("2022-01-01T00:00:00Z"),
                null,
                new Checksum("irrelevant-to-the-rollover"),
                null));

        when(cashFlows.findOpenCashFlowsNeedingRollover(any())).thenReturn(List.of(behind));
    }
}
