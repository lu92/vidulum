package com.multi.vidulum.cashflow.app.commands.rollbackimport;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.shared.cqrs.commands.Command;

/**
 * Command to rollback (clear) all imported historical data from a CashFlow in SETUP mode.
 * This allows the user to start the import process fresh if they made mistakes.
 *
 * @param cashFlowId the CashFlow to rollback
 * @param deleteCategories if true, also delete all custom categories (except Uncategorized)
 */
public record RollbackImportCommand(
        CashFlowId cashFlowId,
        boolean deleteCategories
) implements Command {
}
