package com.multi.vidulum.cashflow.app.commands.rollover;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.shared.cqrs.commands.Command;

import java.time.ZonedDateTime;

/**
 * Command to rollover the current active month to the next month.
 * <p>
 * This command triggers a month transition:
 * <ul>
 *   <li>Current ACTIVE month becomes ROLLED_OVER</li>
 *   <li>Next FORECASTED month becomes ACTIVE</li>
 * </ul>
 * <p>
 * Can be triggered by:
 * <ul>
 *   <li>Scheduled job at the beginning of each month (automatic)</li>
 *   <li>Manual trigger via REST endpoint (for testing or catch-up scenarios)</li>
 * </ul>
 * <p>
 * Unlike the deprecated {@code MakeMonthlyAttestationCommand}, this command:
 * <ul>
 *   <li>Does not require explicit balance confirmation each time</li>
 *   <li>Uses the current calculated balance as the closing balance</li>
 *   <li>Results in ROLLED_OVER status which allows gap filling</li>
 * </ul>
 *
 * @param cashFlowId unique identifier of the cash flow
 * @param dateTime   timestamp when the rollover is triggered
 */
public record RolloverMonthCommand(
        CashFlowId cashFlowId,
        ZonedDateTime dateTime
) implements Command {
}
