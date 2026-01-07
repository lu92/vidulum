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
