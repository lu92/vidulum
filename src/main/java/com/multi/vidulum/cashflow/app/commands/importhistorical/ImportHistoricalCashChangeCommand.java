package com.multi.vidulum.cashflow.app.commands.importhistorical;

import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.shared.cqrs.commands.Command;

import java.time.ZonedDateTime;

/**
 * Command to import a historical cash change into a CashFlow in SETUP mode.
 * This is used for importing historical bank transactions.
 *
 * @param cashFlowId   the cash flow to import to (must be in SETUP mode)
 * @param categoryName the category for this transaction
 * @param name         name/description of the transaction
 * @param description  additional details
 * @param money        the amount
 * @param type         INFLOW or OUTFLOW
 * @param dueDate      when the transaction was due/occurred
 * @param paidDate     when the transaction was actually paid (for historical data, usually same as dueDate)
 */
public record ImportHistoricalCashChangeCommand(
        CashFlowId cashFlowId,
        CategoryName categoryName,
        Name name,
        Description description,
        Money money,
        Type type,
        ZonedDateTime dueDate,
        ZonedDateTime paidDate
) implements Command {
}
