package com.multi.vidulum.cashflow.app;

import com.multi.vidulum.cashflow.app.commands.rollover.RolloverMonthCommand;
import com.multi.vidulum.cashflow.app.commands.rollover.RolloverMonthResult;
import com.multi.vidulum.cashflow.domain.CashFlow;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.DomainCashFlowRepository;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Scheduled job for automatic month rollover.
 * <p>
 * Runs at 02:00 UTC on the 1st day of each month.
 * Finds all CashFlows in OPEN status that have an activePeriod before the current month
 * and performs rollover for each.
 * <p>
 * The scheduler supports catch-up: if a CashFlow's activePeriod is more than one month behind,
 * it will perform multiple rollovers to bring it up to the current month.
 */
@Slf4j
@Component
@AllArgsConstructor
public class MonthlyRolloverScheduler {

    private final DomainCashFlowRepository domainCashFlowRepository;
    private final CommandGateway commandGateway;
    private final Clock clock;

    /**
     * Scheduled job that runs at 02:00 UTC on the 1st of each month.
     * <p>
     * The cron expression "0 0 2 1 * *" means:
     * - Second: 0
     * - Minute: 0
     * - Hour: 2 (02:00)
     * - Day of month: 1
     * - Month: * (every month)
     * - Day of week: * (any)
     */
    @Scheduled(cron = "${vidulum.rollover.cron:0 0 2 1 * *}")
    public void performMonthlyRollover() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        YearMonth currentMonth = YearMonth.from(now);

        log.info("Starting monthly rollover job for period [{}]", currentMonth);

        // Find all OPEN CashFlows that need rollover (activePeriod < currentMonth)
        List<CashFlow> cashFlowsNeedingRollover = domainCashFlowRepository
                .findOpenCashFlowsNeedingRollover(currentMonth);

        if (cashFlowsNeedingRollover.isEmpty()) {
            log.info("No CashFlows need rollover for period [{}]", currentMonth);
            return;
        }

        log.info("Found [{}] CashFlows needing rollover", cashFlowsNeedingRollover.size());

        int successCount = 0;
        int failureCount = 0;

        for (CashFlow cashFlow : cashFlowsNeedingRollover) {
            CashFlowId cashFlowId = cashFlow.getSnapshot().cashFlowId();
            YearMonth activePeriod = cashFlow.getSnapshot().activePeriod();

            try {
                // Perform catch-up rollover if needed (multiple months behind)
                int rolloverCount = performCatchUpRollover(cashFlowId, activePeriod, currentMonth, now);
                log.info("CashFlow [{}] rolled over [{}] month(s) successfully",
                        cashFlowId.id(), rolloverCount);
                successCount++;
            } catch (Exception e) {
                log.error("Failed to rollover CashFlow [{}]: {}", cashFlowId.id(), e.getMessage(), e);
                failureCount++;
            }
        }

        log.info("Monthly rollover job completed. Success: [{}], Failures: [{}]",
                successCount, failureCount);
    }

    /**
     * Performs catch-up rollover for a CashFlow that is behind by one or more months.
     *
     * @param cashFlowId   the CashFlow to rollover
     * @param activePeriod the current active period of the CashFlow
     * @param targetMonth  the target month (current calendar month)
     * @param now          the current timestamp
     * @return the number of rollovers performed
     */
    private int performCatchUpRollover(CashFlowId cashFlowId, YearMonth activePeriod,
                                        YearMonth targetMonth, ZonedDateTime now) {
        int rolloverCount = 0;
        YearMonth currentPeriod = activePeriod;

        // Rollover each month until we reach the target
        while (currentPeriod.isBefore(targetMonth)) {
            RolloverMonthResult result = commandGateway.send(
                    new RolloverMonthCommand(cashFlowId, now)
            );

            log.debug("Rolled over CashFlow [{}] from [{}] to [{}]",
                    cashFlowId.id(), result.rolledOverPeriod(), result.newActivePeriod());

            currentPeriod = result.newActivePeriod();
            rolloverCount++;
        }

        return rolloverCount;
    }

    /**
     * Manual trigger for rollover (useful for testing or catch-up scenarios).
     * <p>
     * This method can be called programmatically to trigger rollover outside the scheduled time.
     */
    public void triggerManualRollover() {
        log.info("Manual rollover triggered");
        performMonthlyRollover();
    }
}
