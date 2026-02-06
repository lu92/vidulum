package com.multi.vidulum.cashflow.app.commands.edit;

import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.Description;
import com.multi.vidulum.cashflow.domain.Name;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.shared.cqrs.commands.Command;

import java.time.ZonedDateTime;

/**
 * Command to edit a CashChange.
 * <p>
 * <b>Full State Update Pattern:</b> Client always sends the complete current state of the CashChange,
 * including category. Even if the category hasn't changed, the current value must be provided.
 * This ensures the server always receives a consistent, complete representation of the entity.
 *
 * @param cashFlowId   unique identifier of the cash flow
 * @param cashChangeId unique identifier of the cash change being edited
 * @param name         updated name
 * @param description  updated description
 * @param money        updated amount
 * @param categoryName category (required - must be current or new category, same type as transaction)
 * @param dueDate      updated due date
 */
public record EditCashChangeCommand(
        CashFlowId cashFlowId,
        CashChangeId cashChangeId,
        Name name,
        Description description,
        Money money,
        CategoryName categoryName,
        ZonedDateTime dueDate) implements Command {
}
