package com.multi.vidulum.bank_data_ingestion.app.commands.stage_transactions;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.shared.cqrs.commands.Command;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Command to stage bank transactions for import.
 * Transactions are validated, mappings are applied, and a preview is generated.
 *
 * @param cashFlowId   the CashFlow to stage transactions for
 * @param transactions list of bank transactions to stage
 */
public record StageTransactionsCommand(
        CashFlowId cashFlowId,
        List<BankTransaction> transactions
) implements Command {

    /**
     * A bank transaction to be staged.
     *
     * @param bankTransactionId unique transaction ID from the bank (for deduplication)
     * @param name              transaction name
     * @param description       additional description (nullable)
     * @param bankCategory      category from bank statement
     * @param money             transaction amount
     * @param type              INFLOW or OUTFLOW
     * @param paidDate          when the transaction was paid
     */
    public record BankTransaction(
            String bankTransactionId,
            String name,
            String description,
            String bankCategory,
            Money money,
            Type type,
            ZonedDateTime paidDate
    ) {
    }
}
