package com.multi.vidulum.cashflow.app;

import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.common.Money;
import lombok.Builder;
import lombok.Data;

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

    @Data
    @Builder
    public static class ConfirmCashChangeJson {
        private String cashFlowId;
        private String cashChangeId;
    }

    @Data
    @Builder
    public static class EditCashChangeJson {
        private String cashFlowId;
        private String cashChangeId;
        private String name;
        private String description;
        private Money money;
        private ZonedDateTime dueDate;
    }

    @Data
    @Builder
    public static class RejectCashChangeJson {
        private String cashFlowId;
        private String cashChangeId;
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
        private Map<String, CashChangeSummaryJson> cashChanges;
        private List<Category> inflowCategories;
        private List<Category> outflowCategories;
        private ZonedDateTime created;
        private ZonedDateTime lastModification;
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
}
