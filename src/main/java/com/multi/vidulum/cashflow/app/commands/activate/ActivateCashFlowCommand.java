package com.multi.vidulum.cashflow.app.commands.activate;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.shared.cqrs.commands.Command;

/**
 * Command to activate a CashFlow, transitioning it from SETUP to OPEN mode.
 * This marks the end of the historical import process.
 *
 * @param cashFlowId           the cash flow to activate (must be in SETUP mode)
 * @param confirmedBalance     the user-confirmed current balance (for validation)
 * @param forceActivation      if true, activate even if calculated balance differs from confirmed
 */
public record ActivateCashFlowCommand(
        CashFlowId cashFlowId,
        Money confirmedBalance,
        boolean forceActivation
) implements Command {
}
