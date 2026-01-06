package com.multi.vidulum.cashflow.app.commands.create;

import com.multi.vidulum.cashflow.domain.BankAccount;
import com.multi.vidulum.cashflow.domain.Description;
import com.multi.vidulum.cashflow.domain.Name;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.commands.Command;

import java.time.YearMonth;

/**
 * Command to create a CashFlow with historical data support.
 * This creates a CashFlow in SETUP mode, allowing import of historical transactions.
 *
 * @param userId       the user who owns this cash flow
 * @param name         name of the cash flow
 * @param description  description of the cash flow
 * @param bankAccount  bank account details with current balance
 * @param startPeriod  the first historical month (e.g., 2024-01 for importing from January 2024)
 * @param initialBalance the balance at the start of startPeriod (opening balance)
 */
public record CreateCashFlowWithHistoryCommand(
        UserId userId,
        Name name,
        Description description,
        BankAccount bankAccount,
        YearMonth startPeriod,
        Money initialBalance
) implements Command {
}
