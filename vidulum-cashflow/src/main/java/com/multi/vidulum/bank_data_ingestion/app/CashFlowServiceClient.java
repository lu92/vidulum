package com.multi.vidulum.bank_data_ingestion.app;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.cashflow.api.Type;

import java.time.LocalDate;

/**
 * What is left of bank-data-ingestion's own view of cashflow.
 *
 * <p><b>This interface is shrinking on purpose, and what it loses goes to
 * {@link com.multi.vidulum.cashflow.api.CashFlowModuleApi}.</b> The difference between the two is
 * not the transport - it is who decides the shape. A port declared here means <i>this module</i>
 * wrote down what cashflow's API looks like, so cashflow cannot change its own payload without
 * breaking a consumer it cannot see. The contract is the other way round: cashflow states what may
 * be asked of it and ships both an in-process and an HTTP implementation.
 *
 * <p>{@code exists} and {@code createCategory} have already moved. The three methods below are
 * waiting on the same treatment, and two of them are harder than they look:
 * {@code getCashFlowInfo} merges cashflow's state with the forecast module's month statuses behind
 * one signature, and {@code importHistoricalTransaction} needs an idempotency key cashflow does
 * not store yet. See {@code docs/features-backlog/2026-10-16-cashflow-module-boundaries.md}.
 */
public interface CashFlowServiceClient {

    /**
     * Get CashFlow information needed for staging and import operations.
     *
     * @param cashFlowId the CashFlow ID
     * @return CashFlowInfo with status, categories, periods, etc.
     * @throws CashFlowNotFoundException if CashFlow does not exist
     */
    CashFlowInfo getCashFlowInfo(String cashFlowId);


    /**
     * Import a historical transaction into CashFlow.
     *
     * @param cashFlowId the CashFlow ID
     * @param request import request with transaction details
     * @return ID of the created CashChange
     */
    String importHistoricalTransaction(String cashFlowId, ImportTransactionRequest request);

    /**
     * Rollback import - delete all transactions and optionally categories.
     *
     * @param cashFlowId the CashFlow ID
     * @param deleteCategories whether to delete categories created during import
     * @return result with counts of deleted items
     */
    RollbackResult rollbackImport(String cashFlowId, boolean deleteCategories);

    // ============ Request/Response DTOs ============

    /**
     * Request for importing a historical transaction.
     */
    record ImportTransactionRequest(
            String categoryName,
            String name,
            String description,
            double amount,
            String currency,
            Type type,
            LocalDate dueDate,
            LocalDate paidDate,
            boolean selfTransfer
    ) {}

    /**
     * Result of rollback operation.
     */
    record RollbackResult(
            int transactionsDeleted,
            int categoriesDeleted,
            CashFlowInfo cashFlowInfoAfter
    ) {}

    // ============ Exceptions ============

    // "No such cash flow" is not declared here any more: it belongs to cashflow, so it is
    // CashFlowNotFoundException from cashflow-api, which carries an ErrorCode and therefore
    // answers 404. The version that used to live here extended RuntimeException with no code, so
    // every call site had to catch it and translate - and any site that forgot answered 500.

    // "Category already exists" is not declared here any more either, for the same reason as
    // "no such cash flow": it is cashflow's failure, so it is CategoryAlreadyExistsException from
    // cashflow-api, carrying ErrorCode.CATEGORY_ALREADY_EXISTS and therefore answering 409. And
    // creating a category is no longer this port's business at all - CashFlowModuleApi.createCategory
    // is, which is why the method above is gone.

    /**
     * Exception thrown when import operation fails.
     */
    class ImportFailedException extends RuntimeException {
        private final String reason;

        public ImportFailedException(String reason) {
            super("Import failed: " + reason);
            this.reason = reason;
        }

        public ImportFailedException(String reason, Throwable cause) {
            super("Import failed: " + reason, cause);
            this.reason = reason;
        }

        public String getReason() {
            return reason;
        }
    }
}
