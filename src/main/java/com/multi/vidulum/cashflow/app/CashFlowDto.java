package com.multi.vidulum.cashflow.app;

import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

public final class CashFlowDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateCashFlowJson {
        @NotBlank(message = "userId is required")
        private String userId;

        @NotBlank(message = "name is required")
        @Size(min = 5, max = 30, message = "CashFlow name must be between 5 and 30 characters")
        private String name;

        private String description;

        @NotNull(message = "bankAccount is required")
        @Valid
        private BankAccountJson bankAccount;

        public BankAccount toBankAccount() {
            if (bankAccount == null) return null;
            return bankAccount.toDomain();
        }
    }

    /**
     * DTO for bank account with validation.
     * Used in request DTOs to validate bank account structure.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BankAccountJson {
        /** Optional bank name (e.g., "Chase Bank") */
        private String bankName;

        @NotNull(message = "bankAccount.bankAccountNumber is required")
        @Valid
        private BankAccountNumberJson bankAccountNumber;

        private MoneyJson balance;

        public BankAccount toDomain() {
            return new BankAccount(
                    bankName != null ? new BankName(bankName) : null,
                    bankAccountNumber != null ? bankAccountNumber.toDomain() : null,
                    balance != null ? balance.toDomain() : null
            );
        }

        /**
         * Creates BankAccountJson from domain BankAccount.
         * Useful for tests.
         */
        public static BankAccountJson from(BankAccount bankAccount) {
            if (bankAccount == null) return null;
            return BankAccountJson.builder()
                    .bankName(bankAccount.bankName() != null ? bankAccount.bankName().name() : null)
                    .bankAccountNumber(BankAccountNumberJson.from(bankAccount.bankAccountNumber()))
                    .balance(bankAccount.balance() != null ? MoneyJson.from(bankAccount.balance()) : null)
                    .build();
        }
    }

    /**
     * DTO for bank account number with validation.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BankAccountNumberJson {
        @NotBlank(message = "bankAccount.bankAccountNumber.account is required")
        private String account;

        @NotNull(message = "bankAccount.bankAccountNumber.denomination is required")
        @Valid
        private CurrencyJson denomination;

        public BankAccountNumber toDomain() {
            return new BankAccountNumber(
                    account,
                    denomination != null ? denomination.toDomain() : null
            );
        }

        public static BankAccountNumberJson from(BankAccountNumber bankAccountNumber) {
            if (bankAccountNumber == null) return null;
            return BankAccountNumberJson.builder()
                    .account(bankAccountNumber.account())
                    .denomination(CurrencyJson.from(bankAccountNumber.denomination()))
                    .build();
        }
    }

    /**
     * DTO for currency with validation.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurrencyJson {
        @NotBlank(message = "bankAccount.bankAccountNumber.denomination.id is required")
        private String id;

        public Currency toDomain() {
            return Currency.of(id);
        }

        public static CurrencyJson from(Currency currency) {
            if (currency == null) return null;
            return CurrencyJson.builder().id(currency.getId()).build();
        }
    }

    /**
     * DTO for money with validation.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MoneyJson {
        @NotNull(message = "amount is required")
        private BigDecimal amount;

        @NotBlank(message = "currency is required")
        private String currency;

        public Money toDomain() {
            return Money.of(amount, currency);
        }

        public static MoneyJson from(Money money) {
            if (money == null) return null;
            return MoneyJson.builder()
                    .amount(money.getAmount())
                    .currency(money.getCurrency())
                    .build();
        }
    }

    /**
     * DTO for creating a CashFlow with historical data support.
     * Creates a CashFlow in SETUP mode for historical data import.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateCashFlowWithHistoryJson {
        @NotBlank(message = "userId is required")
        private String userId;

        @NotBlank(message = "name is required")
        @Size(min = 5, max = 30, message = "CashFlow name must be between 5 and 30 characters")
        private String name;

        private String description;

        @NotNull(message = "bankAccount is required")
        @Valid
        private BankAccountJson bankAccount;

        /** The first historical month (e.g., "2024-01" for importing from January 2024) */
        @NotBlank(message = "startPeriod is required")
        private String startPeriod;

        /** The balance at the start of startPeriod (opening balance) */
        @NotNull(message = "initialBalance is required")
        @Valid
        private MoneyJson initialBalance;

        public BankAccount toBankAccount() {
            if (bankAccount == null) return null;
            return bankAccount.toDomain();
        }

        public Money toInitialBalance() {
            if (initialBalance == null) return null;
            return initialBalance.toDomain();
        }
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
        @NotNull(message = "confirmedBalance is required")
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

    /**
     * Response for month rollover operation.
     * <p>
     * Rollover transitions the current ACTIVE month to ROLLED_OVER status
     * and the next FORECASTED month becomes ACTIVE.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RolloverMonthResponseJson {
        /** The CashFlow that was rolled over */
        private String cashFlowId;
        /** The period that was rolled over (now has ROLLED_OVER status) */
        private YearMonth rolledOverPeriod;
        /** The new active period (now has ACTIVE status) */
        private YearMonth newActivePeriod;
        /** The balance at the end of the rolled over period */
        private Money closingBalance;
    }

    /**
     * Response for batch rollover operation (catch-up rollover).
     * Used when multiple months need to be rolled over at once.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchRolloverResponseJson {
        /** The CashFlow that was rolled over */
        private String cashFlowId;
        /** Number of months that were rolled over */
        private int monthsRolledOver;
        /** The first period that was rolled over */
        private YearMonth firstRolledOverPeriod;
        /** The last period that was rolled over */
        private YearMonth lastRolledOverPeriod;
        /** The new active period after all rollovers */
        private YearMonth newActivePeriod;
        /** The final closing balance */
        private Money closingBalance;
    }
}
