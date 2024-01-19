package com.multi.vidulum.cashflow.domain.snapshots;

import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.EntitySnapshot;

import java.time.ZonedDateTime;

public record CashChangeSnapshot(
        CashChangeId cashChangeId,
        UserId userId, Name name,
        Description description,
        Money money,
        Type type, CashChangeStatus status,
        ZonedDateTime created,
        ZonedDateTime dueDate,
        ZonedDateTime endDate) implements EntitySnapshot<CashChangeId> {

    @Override
    public CashChangeId id() {
        return cashChangeId;
    }
}
