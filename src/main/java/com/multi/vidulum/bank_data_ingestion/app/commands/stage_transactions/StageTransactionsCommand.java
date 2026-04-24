package com.multi.vidulum.bank_data_ingestion.app.commands.stage_transactions;

import com.multi.vidulum.bank_data_adapter.domain.TransactionClassification;
import com.multi.vidulum.bank_data_ingestion.domain.PaymentMethod;
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
 * @param metadata     optional session metadata (from AI transformation or manual upload)
 */
public record StageTransactionsCommand(
        CashFlowId cashFlowId,
        List<BankTransaction> transactions,
        SessionMetadata metadata
) implements Command {

    /**
     * Constructor without metadata (for backward compatibility).
     */
    public StageTransactionsCommand(CashFlowId cashFlowId, List<BankTransaction> transactions) {
        this(cashFlowId, transactions, null);
    }

    /**
     * Optional metadata about the staging session source.
     * Populated when session is created from AI transformation.
     *
     * @param transformationId  ID of the AI transformation that created this session (nullable)
     * @param detectedLanguage  language detected by AI (e.g., "pl", "en", "de")
     * @param detectedBank      bank detected by AI (e.g., "Nest Bank", "PKO BP")
     * @param detectedCountry   country detected from bank or IBAN (e.g., "PL", "DE")
     * @param originalFileName  original uploaded file name
     * @param createdByUserId   user ID who created this session
     */
    public record SessionMetadata(
            String transformationId,
            String detectedLanguage,
            String detectedBank,
            String detectedCountry,
            String originalFileName,
            String createdByUserId
    ) {}

    /**
     * A bank transaction to be staged.
     *
     * @param bankTransactionId unique transaction ID from the bank (for deduplication)
     * @param name              transaction name
     * @param description       additional description (nullable)
     * @param bankCategory      category from bank statement (WHAT was purchased)
     * @param money             transaction amount
     * @param type              INFLOW or OUTFLOW
     * @param paidDate          when the transaction was paid
     * @param merchant          extracted merchant name (nullable) - for bank intermediary transactions
     * @param merchantConfidence confidence score for merchant extraction (0.0-1.0, nullable)
     * @param counterpartyAccount the other party's bank account number (for OUTFLOW: recipient, for INFLOW: sender)
     * @param paymentMethod     payment method used (HOW payment was made: CARD, TRANSFER, BLIK, etc.)
     * @param classification    transaction classification (MERCHANT, BANK_FEE, CASH_WITHDRAWAL, etc.)
     */
    public record BankTransaction(
            String bankTransactionId,
            String name,
            String description,
            String bankCategory,
            Money money,
            Type type,
            ZonedDateTime paidDate,
            String merchant,
            Double merchantConfidence,
            String counterpartyAccount,
            PaymentMethod paymentMethod,
            TransactionClassification classification
    ) {
    }
}
