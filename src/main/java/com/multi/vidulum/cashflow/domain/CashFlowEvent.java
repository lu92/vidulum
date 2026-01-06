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
        CashFlowEvent.CashFlowWithHistoryCreatedEvent,
        CashFlowEvent.HistoricalImportAttestedEvent,
        CashFlowEvent.ImportRolledBackEvent,
        CashFlowEvent.HistoricalCashChangeImportedEvent,
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

    /**
     * Event for creating a CashFlow with historical data support.
     * Creates a CashFlow in SETUP mode with historical months marked as IMPORT_PENDING.
     *
     * @param cashFlowId     unique identifier of the cash flow
     * @param userId         the user who owns this cash flow
     * @param name           name of the cash flow
     * @param description    description of the cash flow
     * @param bankAccount    bank account details with current balance
     * @param startPeriod    the first historical month
     * @param activePeriod   the current active month (derived from created timestamp)
     * @param initialBalance the balance at the start of startPeriod
     * @param created        timestamp when the cash flow was created
     */
    record CashFlowWithHistoryCreatedEvent(
            CashFlowId cashFlowId,
            UserId userId,
            Name name,
            Description description,
            BankAccount bankAccount,
            YearMonth startPeriod,
            YearMonth activePeriod,
            Money initialBalance,
            ZonedDateTime created
    ) implements CashFlowEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return created;
        }
    }

    /**
     * Event for importing a historical cash change into a CashFlow in SETUP mode.
     * Historical transactions are imported as already CONFIRMED.
     *
     * @param cashFlowId    unique identifier of the cash flow
     * @param cashChangeId  unique identifier of the imported cash change
     * @param name          name/description of the transaction
     * @param description   additional details
     * @param money         the amount
     * @param type          INFLOW or OUTFLOW
     * @param categoryName  the category for this transaction
     * @param dueDate       when the transaction was due/occurred
     * @param paidDate      when the transaction was actually paid
     * @param importedAt    timestamp when the import occurred
     */
    record HistoricalCashChangeImportedEvent(
            CashFlowId cashFlowId,
            CashChangeId cashChangeId,
            Name name,
            Description description,
            Money money,
            Type type,
            CategoryName categoryName,
            ZonedDateTime dueDate,
            ZonedDateTime paidDate,
            ZonedDateTime importedAt
    ) implements CashFlowEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return importedAt;
        }
    }

    /**
     * Event for attesting a historical import, transitioning CashFlow from SETUP to OPEN mode.
     * This marks the end of the historical import process and confirms the balance.
     *
     * @param cashFlowId        unique identifier of the cash flow
     * @param confirmedBalance  the user-confirmed current balance
     * @param calculatedBalance the system-calculated balance based on imports
     * @param balanceDifference the difference between confirmed and calculated (0 if matching)
     * @param forced            true if attestation was forced despite balance mismatch
     * @param attestedAt        timestamp when attestation occurred (becomes importCutoffDateTime)
     */
    record HistoricalImportAttestedEvent(
            CashFlowId cashFlowId,
            Money confirmedBalance,
            Money calculatedBalance,
            Money balanceDifference,
            boolean forced,
            ZonedDateTime attestedAt
    ) implements CashFlowEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return attestedAt;
        }
    }

    /**
     * Event for rolling back (clearing) all imported historical data from a CashFlow in SETUP mode.
     * This clears all imported transactions and optionally categories, allowing a fresh start.
     *
     * @param cashFlowId                 unique identifier of the cash flow
     * @param deletedTransactionsCount   number of transactions that were deleted
     * @param deletedCategoriesCount     number of categories that were deleted (0 if deleteCategories was false)
     * @param categoriesDeleted          true if custom categories were also deleted
     * @param rolledBackAt               timestamp when rollback occurred
     */
    record ImportRolledBackEvent(
            CashFlowId cashFlowId,
            int deletedTransactionsCount,
            int deletedCategoriesCount,
            boolean categoriesDeleted,
            ZonedDateTime rolledBackAt
    ) implements CashFlowEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return rolledBackAt;
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
