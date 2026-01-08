package com.multi.vidulum.bank_data_ingestion.app;

import com.multi.vidulum.cashflow.domain.Type;

import java.time.LocalDate;

/**
 * Client interface for communicating with CashFlow service.
 *
 * This abstraction allows bank-data-ingestion module to be decoupled from the CashFlow domain,
 * enabling future migration to a microservice architecture where this interface would be
 * implemented by an HTTP client instead of direct in-process calls.
 *
 * Current implementation: LocalCashFlowServiceClient (in-process, uses repositories and CommandGateway)
 * Future implementation: HttpCashFlowServiceClient (HTTP calls to cashflow-service)
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
     * Check if CashFlow exists.
     *
     * @param cashFlowId the CashFlow ID
     * @return true if exists
     */
    boolean exists(String cashFlowId);

    /**
     * Create a new category in CashFlow.
     *
     * @param cashFlowId the CashFlow ID
     * @param categoryName name of the new category
     * @param parentCategoryName parent category name (null for top-level)
     * @param type INFLOW or OUTFLOW
     * @throws CategoryAlreadyExistsException if category already exists
     */
    void createCategory(String cashFlowId, String categoryName, String parentCategoryName, Type type);

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
            LocalDate paidDate
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

    /**
     * Exception thrown when CashFlow is not found.
     */
    class CashFlowNotFoundException extends RuntimeException {
        private final String cashFlowId;

        public CashFlowNotFoundException(String cashFlowId) {
            super("CashFlow not found: " + cashFlowId);
            this.cashFlowId = cashFlowId;
        }

        public String getCashFlowId() {
            return cashFlowId;
        }
    }

    /**
     * Exception thrown when category already exists.
     */
    class CategoryAlreadyExistsException extends RuntimeException {
        private final String categoryName;

        public CategoryAlreadyExistsException(String categoryName) {
            super("Category already exists: " + categoryName);
            this.categoryName = categoryName;
        }

        public String getCategoryName() {
            return categoryName;
        }
    }

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
