package com.multi.vidulum.cashflow.app.commands.update;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.Name;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.shared.cqrs.commands.Command;

import java.util.List;

/**
 * Command to update multiple PENDING (expected) cash changes in batch.
 * <p>
 * Used primarily by Recurring Rules module when editing rule amount/category.
 * Only PENDING cash changes are updated - CONFIRMED transactions are silently skipped.
 * <p>
 * Uses explicit list of cash change IDs instead of searching by sourceRuleId in database,
 * which avoids race condition issues with eventual consistency.
 *
 * @param cashFlowId     unique identifier of the cash flow
 * @param sourceRuleId   the recurring rule ID (for audit/logging purposes only)
 * @param cashChangeIds  explicit list of cash change IDs to update
 * @param updates        the changes to apply
 */
public record BatchUpdateCashChangesCommand(
        CashFlowId cashFlowId,
        String sourceRuleId,
        List<CashChangeId> cashChangeIds,
        CashChangeUpdates updates) implements Command {

    /**
     * Represents the fields that can be updated in a batch operation.
     * All fields are optional - only non-null fields will be applied.
     *
     * @param amount       new amount (null = don't change)
     * @param name         new name (null = don't change)
     * @param categoryName new category (null = don't change)
     */
    public record CashChangeUpdates(
            Money amount,
            Name name,
            CategoryName categoryName) {
    }
}
