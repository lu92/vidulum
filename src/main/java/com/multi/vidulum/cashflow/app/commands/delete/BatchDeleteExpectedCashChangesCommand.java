package com.multi.vidulum.cashflow.app.commands.delete;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.shared.cqrs.commands.Command;

import java.util.List;

/**
 * Command to delete multiple PENDING (expected) cash changes in batch.
 * <p>
 * Used primarily by Recurring Rules module when deleting a rule or changing its schedule.
 * Only PENDING cash changes are deleted - CONFIRMED transactions are silently skipped.
 * <p>
 * Uses explicit list of cash change IDs instead of searching by sourceRuleId in database,
 * which avoids race condition issues with eventual consistency.
 *
 * @param cashFlowId     unique identifier of the cash flow
 * @param sourceRuleId   the recurring rule ID (for audit/logging purposes only)
 * @param cashChangeIds  explicit list of cash change IDs to delete
 */
public record BatchDeleteExpectedCashChangesCommand(
        CashFlowId cashFlowId,
        String sourceRuleId,
        List<CashChangeId> cashChangeIds) implements Command {
}
