package com.multi.vidulum.cashflow.domain.snapshots;

import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.EntitySnapshot;

import java.time.ZonedDateTime;
import java.util.Map;

public record CashFlowSnapshot(
        CashFlowId cashFlowId,
        UserId userId,
        Name name,
        Description description,
        Money balance,
        CashFlow.CashFlowStatus status,
        Map<CashChangeId, CashChangeSnapshot> cashChanges,

        ZonedDateTime created,
        ZonedDateTime lastModification) implements EntitySnapshot<CashFlowId> {

    @Override
    public CashFlowId id() {
        return cashFlowId;
    }
}
