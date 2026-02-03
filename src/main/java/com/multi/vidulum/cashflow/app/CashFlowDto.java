package com.multi.vidulum.cashflow.app;

import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.common.Money;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

public final class CashFlowDto {

    @Data
    @Builder
    public static class CreateCashFlowJson {
        private String userId;
        private String name;
        private String description;
        private BankAccount bankAccount;
    }

    /**
     * DTO for creating a CashFlow with historical data support.
     * Creates a CashFlow in SETUP mode for historical data import.
     */
    @Data
    @Builder
    public static class CreateCashFlowWithHistoryJson {
        private String userId;
        private String name;
        private String description;
        private BankAccount bankAccount;
        /** The first historical month (e.g., "2024-01" for importing from January 2024) */
        private String startPeriod;
        /** The balance at the start of startPeriod (opening balance) */
        private Money initialBalance;
    }

    @Data
    @Builder
    public static class AppendExpectedCashChangeJson {
        private String cashFlowId;
        private String category;
        private String name;
        private String description;
        private Money money;
        private Type type;
        private ZonedDateTime dueDate;
    }

    @Data
    @Builder
    public static class AppendPaidCashChangeJson {
        private String cashFlowId;
        private String category;
        private String name;
        private String description;
        private Money money;
        private Type type;
        private ZonedDateTime dueDate;
        private ZonedDateTime paidDate;
    }

    /**
     * DTO for importing a historical cash change.
     * Used for importing past transactions during SETUP mode.
     */
    @Data
    @Builder
    public static class ImportHistoricalCashChangeJson {
        private String category;
        private String name;
        private String description;
        private Money money;
        private Type type;
        private ZonedDateTime dueDate;
        private ZonedDateTime paidDate;
    }

    /**
     * DTO for attesting a historical import.
     * Transitions the CashFlow from SETUP to OPEN mode.
     */
    @Data
    @Builder
    public static class AttestHistoricalImportJson {
        /** The user-confirmed current balance (for validation against calculated balance) */
        private Money confirmedBalance;
        /** If true, attest even if confirmed balance differs from calculated balance */
        private boolean forceAttestation;
        /** If true and balance differs, create an adjustment transaction to reconcile the difference */
        private boolean createAdjustment;
    }

    /**
     * Response DTO for historical import attestation.
     */
    @Data
    @Builder
    public static class AttestHistoricalImportResponseJson {
        private String cashFlowId;
        private Money confirmedBalance;
        private Money calculatedBalance;
        private Money difference;
        private boolean forced;
        private boolean adjustmentCreated;
        private String adjustmentCashChangeId;
        private CashFlow.CashFlowStatus status;
    }

    /**
     * DTO for rolling back (clearing) imported historical data.
     * Allows the user to start the import process fresh.
     */
    @Data
    @Builder
    public static class RollbackImportJson {
        /** If true, also delete all custom categories (except Uncategorized) */
        private boolean deleteCategories;
    }

    /**
     * Response DTO for import rollback.
     */
    @Data
    @Builder
    public static class RollbackImportResponseJson {
        private String cashFlowId;
        private int deletedTransactionsCount;
        private int deletedCategoriesCount;
        private boolean categoriesDeleted;
        private CashFlow.CashFlowStatus status;
    }

    @Data
    @Builder
    public static class ConfirmCashChangeJson {
        @NotBlank(message = "CashFlow ID is required")
        private String cashFlowId;

        @NotBlank(message = "CashChange ID is required")
        private String cashChangeId;
    }

    /**
     * DTO for editing a CashChange.
     * <p>
     * <b>Full State Update Pattern:</b> Client always sends the complete current state of the CashChange,
     * including category. Even if the category hasn't changed, the current value must be provided.
     * This ensures the server always receives a consistent, complete representation of the entity.
     * <p>
     * Category must be of the same type (INFLOW/OUTFLOW) as the original transaction.
     * Only non-archived categories are allowed.
     */
    @Data
    @Builder
    public static class EditCashChangeJson {
        @NotBlank(message = "CashFlow ID is required")
        private String cashFlowId;

        @NotBlank(message = "CashChange ID is required")
        private String cashChangeId;

        @NotBlank(message = "Name is required")
        private String name;

        @Size(max = 500, message = "Description cannot exceed 500 characters")
        private String description;

        @NotNull(message = "Money is required")
        private Money money;

        @NotBlank(message = "Category is required")
        private String category;

        @NotNull(message = "Due date is required")
        private ZonedDateTime dueDate;
    }

    @Data
    @Builder
    public static class RejectCashChangeJson {
        @NotBlank(message = "CashFlow ID is required")
        private String cashFlowId;

        @NotBlank(message = "CashChange ID is required")
        private String cashChangeId;

        @NotBlank(message = "Reason is required")
        private String reason;
    }

    @Data
    @Builder
    public static class CashFlowSummaryJson {
        private String cashFlowId;
        private String userId;
        private String name;
        private String description;
        private BankAccount bankAccount;
        private CashFlow.CashFlowStatus status;
        private YearMonth activePeriod;
        private YearMonth startPeriod;
        private Map<String, CashChangeSummaryJson> cashChanges;
        private List<Category> inflowCategories;
        private List<Category> outflowCategories;
        private ZonedDateTime created;
        private ZonedDateTime lastModification;
        private ZonedDateTime importCutoffDateTime;
        private String lastMessageChecksum;
    }

    @Data
    @Builder
    public static class CashFlowDetailJson {
        private String cashFlowId;
        private String userId;
        private String name;
        private String description;
        private BankAccount bankAccount;
        private CashFlow.CashFlowStatus status;
        private List<Category> inflowCategories;
        private List<Category> outflowCategories;
        private ZonedDateTime created;
        private ZonedDateTime lastModification;
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class CashChangeSummaryJson {
        private String cashChangeId;
        private String name;
        private String description;
        private Money money;
        private Type type;
        private String categoryName;
        private CashChangeStatus status;
        private ZonedDateTime created;
        private ZonedDateTime dueDate;
        private ZonedDateTime endDate;
    }

    @Data
    @Builder
    public static class CreateCategoryJson {
        private String parentCategoryName; //optional
        private String category;
        private Type type;
    }

    @Data
    @Builder
    public static class SetBudgetingJson {
        private String cashFlowId;
        private String categoryName;
        private Type categoryType;
        private Money budget;
    }

    @Data
    @Builder
    public static class UpdateBudgetingJson {
        private String cashFlowId;
        private String categoryName;
        private Type categoryType;
        private Money newBudget;
    }

    @Data
    @Builder
    public static class RemoveBudgetingJson {
        private String cashFlowId;
        private String categoryName;
        private Type categoryType;
    }

    /**
     * Request to archive a category.
     */
    @Data
    @Builder
    public static class ArchiveCategoryJson {
        private String categoryName;
        private Type categoryType;
        /**
         * If true, all subcategories will be archived along with the parent category.
         * If false and the category has active subcategories, only the parent will be archived.
         */
        private boolean forceArchiveChildren;
    }

    /**
     * Request to unarchive a category.
     */
    @Data
    @Builder
    public static class UnarchiveCategoryJson {
        private String categoryName;
        private Type categoryType;
    }

    /**
     * Response with category details including archiving status.
     */
    @Data
    @Builder
    public static class CategoryJson {
        private String categoryName;
        private boolean isModifiable;
        private boolean archived;
        private String origin;
        private ZonedDateTime validFrom;
        private ZonedDateTime validTo;
        private Money budget;
        private List<CategoryJson> subCategories;
    }
}
