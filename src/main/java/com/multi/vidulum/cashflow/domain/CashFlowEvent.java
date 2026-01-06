package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Reason;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.event.DomainEvent;

import java.time.YearMonth;
import java.time.ZonedDateTime;

public sealed interface CashFlowEvent extends DomainEvent
        permits
        CashFlowEvent.CashFlowCreatedEvent,
        CashFlowEvent.MonthAttestedEvent,
        CashFlowEvent.ExpectedCashChangeAppendedEvent,
        CashFlowEvent.PaidCashChangeAppendedEvent,
        CashFlowEvent.CashChangeConfirmedEvent,
        CashFlowEvent.CashChangeEditedEvent,
        CashFlowEvent.CashChangeRejectedEvent,
        CashFlowEvent.CategoryCreatedEvent,
        CashFlowEvent.BudgetingSetEvent,
        CashFlowEvent.BudgetingUpdatedEvent,
        CashFlowEvent.BudgetingRemovedEvent {

    CashFlowId cashFlowId();

    ZonedDateTime occurredAt();

    record CashFlowCreatedEvent(CashFlowId cashFlowId, UserId userId, Name name, Description description,
                                BankAccount bankAccount,
                                ZonedDateTime created) implements CashFlowEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return created;
        }
    }

    record MonthAttestedEvent(CashFlowId cashFlowId, YearMonth period, Money currentMoney,
                              ZonedDateTime dateTime) implements CashFlowEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return dateTime;
        }
    }

    record ExpectedCashChangeAppendedEvent(CashFlowId cashFlowId, CashChangeId cashChangeId, Name name, Description description,
                                   Money money, Type type, ZonedDateTime created, CategoryName categoryName,
                                   ZonedDateTime dueDate) implements CashFlowEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return created;
        }
    }

    record PaidCashChangeAppendedEvent(CashFlowId cashFlowId, CashChangeId cashChangeId, Name name, Description description,
                                       Money money, Type type, ZonedDateTime created, CategoryName categoryName,
                                       ZonedDateTime dueDate, ZonedDateTime paidDate) implements CashFlowEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return created;
        }
    }

    record CashChangeConfirmedEvent(CashFlowId cashFlowId, CashChangeId cashChangeId,
                                    ZonedDateTime endDate) implements CashFlowEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return endDate;
        }
    }

    record CashChangeEditedEvent(CashFlowId cashFlowId, CashChangeId cashChangeId, Name name, Description description,
                                 Money money, ZonedDateTime dueDate, ZonedDateTime editedAt) implements CashFlowEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return editedAt;
        }
    }

    record CashChangeRejectedEvent(CashFlowId cashFlowId, CashChangeId cashChangeId,
                                   Reason reason, ZonedDateTime rejectedAt) implements CashFlowEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return rejectedAt;
        }
    }

    record CategoryCreatedEvent(
            CashFlowId cashFlowId,
            CategoryName parentCategoryName,
            CategoryName categoryName,
            Type type,
            ZonedDateTime createdAt
    ) implements CashFlowEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return createdAt;
        }
    }

    record BudgetingSetEvent(
            CashFlowId cashFlowId,
            CategoryName categoryName,
            Type categoryType,
            Money budget,
            ZonedDateTime created
    ) implements CashFlowEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return created;
        }
    }

    record BudgetingUpdatedEvent(
            CashFlowId cashFlowId,
            CategoryName categoryName,
            Type categoryType,
            Money newBudget,
            ZonedDateTime updated
    ) implements CashFlowEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return updated;
        }
    }

    record BudgetingRemovedEvent(
            CashFlowId cashFlowId,
            CategoryName categoryName,
            Type categoryType,
            ZonedDateTime removed
    ) implements CashFlowEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return removed;
        }
    }
}
