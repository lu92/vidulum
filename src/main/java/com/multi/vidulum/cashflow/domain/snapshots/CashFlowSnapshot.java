package com.multi.vidulum.cashflow.domain.snapshots;

import com.multi.vidulum.cashflow.domain.*;
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
        Checksum lastMessageChecksum) implements EntitySnapshot<CashFlowId> {

    @Override
    public CashFlowId id() {
        return cashFlowId;
    }
}
