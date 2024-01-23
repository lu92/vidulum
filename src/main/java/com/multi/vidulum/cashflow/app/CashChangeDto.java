package com.multi.vidulum.cashflow.app;

import com.multi.vidulum.cashflow.domain.CashChangeStatus;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.common.Money;
import lombok.Builder;
import lombok.Data;

import java.time.ZonedDateTime;

public final class CashChangeDto {

    @Data
    @Builder
    public static class CreateEmptyCashChangeJson {
        private String userId;
        private String name;
        private String description;
        private Money money;
        private Type type;
        private ZonedDateTime dueDate;
    }

    @Data
    @Builder
    public static class ConfirmCashChangeJson {
        private String cashChangeId;
    }

    @Data
    @Builder
    public static class EditCashChangeJson {
        private String cashChangeId;
        private String name;
        private String description;
        private Money money;
        private ZonedDateTime dueDate;
    }

    @Data
    @Builder
    public static class CashChangeSummaryJson {
        private String cashChangeId;
        private String userId;
        private String name;
        private String description;
        private Type type;
        private CashChangeStatus status;
        private ZonedDateTime created;
        private ZonedDateTime dueDate;
    }
}
