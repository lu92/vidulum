package com.multi.vidulum.cashflow.domain.snapshots;
import com.multi.vidulum.common.CashChangeId;import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow.api.view.CashFlowSkeletonView;
import com.multi.vidulum.cashflow.api.view.ImportWindow;
import com.multi.vidulum.common.Checksum;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.EntitySnapshot;

import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of CashFlow aggregate state for persistence and transfer.
 *
 * @param cashFlowId           unique identifier
 * @param userId               owner of this CashFlow
 * @param name                 display name
 * @param description          optional description
 * @param bankAccount          associated bank account with current balance
 * @param status               current lifecycle status (SETUP, OPEN, CLOSED)
 * @param cashChanges          all transactions (both expected and confirmed)
 * @param startPeriod          first month of CashFlow (historical start for Advanced Setup)
 * @param activePeriod         current month ("now")
 * @param initialBalance       opening balance at startPeriod (used for balance calculation)
 * @param inflowCategories     income categories
 * @param outflowCategories    expense categories
 * @param created              creation timestamp
 * @param lastModification     last modification timestamp
 * @param importCutoffDateTime timestamp when historical import was attested (null before attestation);
 *                             marks boundary between historical data and new transactions
 * @param lastMessageChecksum  checksum for event synchronization
 */
public record CashFlowSnapshot(
        CashFlowId cashFlowId,
        UserId userId,
        Name name,
        Description description,
        BankAccount bankAccount,
        CashFlow.CashFlowStatus status,
        Map<CashChangeId, CashChangeSnapshot> cashChanges,
        YearMonth startPeriod,
        YearMonth activePeriod,
        Money initialBalance,
        List<Category> inflowCategories,
        List<Category> outflowCategories,
        ZonedDateTime created,
        ZonedDateTime lastModification,
        ZonedDateTime importCutoffDateTime,
        Checksum lastMessageChecksum,
        /**
         * The stored document's version, or {@code null} for a cash flow that has never been saved.
         *
         * <p>Infrastructure in a domain record, and so is {@code lastMessageChecksum} right above it
         * - this record is the shape the aggregate takes in order to be stored, which is exactly
         * what a version belongs to. It has to travel: {@code save} rebuilds the entity from the
         * snapshot, so a version left behind here would reach MongoDB as {@code null}, and a null
         * version means "new document" - the write would stop being a conditional update and go back
         * to overwriting whatever is there (task F35).
         */
        Long version) implements EntitySnapshot<CashFlowId> {

    @Override
    public CashFlowId id() {
        return cashFlowId;
    }

    /**
     * This cash flow's status in the contract's vocabulary.
     *
     * <p>The one place the aggregate's {@code CashFlowStatus} maps onto
     * {@code LifecyclePhase}. A {@code switch} rather than {@code valueOf}, so adding a status stops
     * compiling here instead of surprising a consumer at runtime - and in one place rather than
     * three, which is what it was heading for.
     */
    public CashFlowSkeletonView.LifecyclePhase phase() {
        return switch (status) {
            case SETUP -> CashFlowSkeletonView.LifecyclePhase.SETUP;
            case OPEN -> CashFlowSkeletonView.LifecyclePhase.OPEN;
            case CLOSED -> CashFlowSkeletonView.LifecyclePhase.CLOSED;
        };
    }

    /**
     * Which dates this cash flow will accept an import for.
     *
     * <p>The same rule {@code CashFlowSkeletonView.importWindow()} hands to a consumer, built from
     * the aggregate's own state - so the handler that enforces it and the staging that pre-checks it
     * are running the identical code rather than two copies of one rule.
     */
    public ImportWindow importWindow() {
        return new ImportWindow(cashFlowId, phase(), activePeriod, startPeriod);
    }
}
