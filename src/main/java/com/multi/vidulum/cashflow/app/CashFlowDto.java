package com.multi.vidulum.cashflow.app;

import com.multi.vidulum.cashflow.domain.BankAccount;
import com.multi.vidulum.cashflow.domain.CashChangeStatus;
import com.multi.vidulum.cashflow.domain.CashFlow;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.common.Money;
import lombok.Builder;
import lombok.Data;

import java.time.ZonedDateTime;
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

    @Data
    @Builder
    public static class AppendCashChangeJson {
        private String cashFlowId;
        private String name;
        private String description;
        private Money money;
        private Type type;
        private ZonedDateTime dueDate;
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
        private CashChangeStatus status;
        private ZonedDateTime created;
        private ZonedDateTime dueDate;
        private ZonedDateTime endDate;
    }
}
