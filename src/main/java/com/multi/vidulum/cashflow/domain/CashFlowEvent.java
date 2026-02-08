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
        CashFlowEvent.MonthRolledOverEvent,
        CashFlowEvent.ExpectedCashChangeAppendedEvent,
        CashFlowEvent.PaidCashChangeAppendedEvent,
        CashFlowEvent.CashChangeConfirmedEvent,
        CashFlowEvent.CashChangeEditedEvent,
        CashFlowEvent.CashChangeRejectedEvent,
        CashFlowEvent.CategoryCreatedEvent,
        CashFlowEvent.CategoryArchivedEvent,
        CashFlowEvent.CategoryUnarchivedEvent,
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
     * @param cashFlowId            unique identifier of the cash flow
     * @param confirmedBalance      the user-confirmed current balance
     * @param calculatedBalance     the system-calculated balance based on imports
     * @param balanceDifference     the difference between confirmed and calculated (0 if matching)
     * @param forced                true if attestation was forced despite balance mismatch
     * @param adjustmentCashChangeId if createAdjustment was used, the ID of the adjustment transaction (null otherwise)
     * @param attestedAt            timestamp when attestation occurred (becomes importCutoffDateTime)
     */
    record HistoricalImportAttestedEvent(
            CashFlowId cashFlowId,
            Money confirmedBalance,
            Money calculatedBalance,
            Money balanceDifference,
            boolean forced,
            CashChangeId adjustmentCashChangeId,
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

    /**
     * @deprecated Use {@link MonthRolledOverEvent} instead. This event is kept for backward compatibility.
     */
    @Deprecated
    record MonthAttestedEvent(CashFlowId cashFlowId, YearMonth period, Money currentMoney,
                              ZonedDateTime dateTime) implements CashFlowEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return dateTime;
        }
    }

    /**
     * Event for automatic or manual month rollover.
     * <p>
     * This event transitions the current ACTIVE month to ROLLED_OVER status
     * and the next FORECASTED month becomes ACTIVE.
     * <p>
     * Rollover can be triggered by:
     * <ul>
     *   <li>Scheduled job at the beginning of each month (automatic)</li>
     *   <li>Manual trigger via REST endpoint (for testing or catch-up)</li>
     * </ul>
     * <p>
     * Unlike the deprecated {@link MonthAttestedEvent}, rollover:
     * <ul>
     *   <li>Does not require balance verification each time</li>
     *   <li>Can be triggered automatically without user intervention</li>
     *   <li>Allows gap filling to ROLLED_OVER months later</li>
     * </ul>
     *
     * @param cashFlowId      unique identifier of the cash flow
     * @param rolledOverPeriod the period that was rolled over (becomes ROLLED_OVER)
     * @param newActivePeriod  the new active period (becomes ACTIVE)
     * @param closingBalance   the balance at the end of the rolled over period
     * @param rolledOverAt     timestamp when the rollover occurred
     */
    record MonthRolledOverEvent(
            CashFlowId cashFlowId,
            YearMonth rolledOverPeriod,
            YearMonth newActivePeriod,
            Money closingBalance,
            ZonedDateTime rolledOverAt
    ) implements CashFlowEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return rolledOverAt;
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

    /**
     * Event for editing a CashChange.
     * <p>
     * <b>Full State Update Pattern:</b> Client always sends the complete current state of the CashChange,
     * including category. Even if the category hasn't changed, the current value must be provided.
     * This ensures the server always receives a consistent, complete representation of the entity.
     *
     * @param cashFlowId    unique identifier of the cash flow
     * @param cashChangeId  unique identifier of the cash change being edited
     * @param name          updated name
     * @param description   updated description
     * @param money         updated amount
     * @param categoryName  category (required - must be current or new category, same type as transaction)
     * @param dueDate       updated due date
     * @param editedAt      timestamp when the edit occurred
     */
    record CashChangeEditedEvent(CashFlowId cashFlowId, CashChangeId cashChangeId, Name name, Description description,
                                 Money money, CategoryName categoryName, ZonedDateTime dueDate, ZonedDateTime editedAt) implements CashFlowEvent {
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

    /**
     * Event for archiving a category, hiding it from new transactions.
     * Archived categories remain visible in historical transactions that used them.
     *
     * @param cashFlowId           the CashFlow containing the category
     * @param categoryName         the name of the archived category
     * @param categoryType         INFLOW or OUTFLOW
     * @param archivedAt           timestamp when the category was archived (becomes validTo)
     * @param forceArchiveChildren if true, all subcategories are also archived
     */
    record CategoryArchivedEvent(
            CashFlowId cashFlowId,
            CategoryName categoryName,
            Type categoryType,
            ZonedDateTime archivedAt,
            boolean forceArchiveChildren
    ) implements CashFlowEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return archivedAt;
        }
    }

    /**
     * Event for unarchiving a category, making it available for new transactions again.
     *
     * @param cashFlowId   the CashFlow containing the category
     * @param categoryName the name of the unarchived category
     * @param categoryType INFLOW or OUTFLOW
     * @param unarchivedAt timestamp when the category was unarchived
     */
    record CategoryUnarchivedEvent(
            CashFlowId cashFlowId,
            CategoryName categoryName,
            Type categoryType,
            ZonedDateTime unarchivedAt
    ) implements CashFlowEvent {
        @Override
        public ZonedDateTime occurredAt() {
            return unarchivedAt;
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
