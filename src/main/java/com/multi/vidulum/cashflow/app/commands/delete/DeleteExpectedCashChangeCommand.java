package com.multi.vidulum.cashflow.app.commands.delete;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.shared.cqrs.commands.Command;

/**
 * Command to delete a single PENDING (expected) cash change.
 * <p>
 * Used primarily by Recurring Rules module when deleting individual transactions.
 * Only PENDING cash changes can be deleted - CONFIRMED transactions are protected.
 *
 * @param cashFlowId   unique identifier of the cash flow
 * @param cashChangeId unique identifier of the cash change to delete
 */
public record DeleteExpectedCashChangeCommand(
        CashFlowId cashFlowId,
        CashChangeId cashChangeId) implements Command {
}
