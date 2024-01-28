package com.multi.vidulum.cashflow.domain.snapshots;

import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.common.Money;

import java.time.ZonedDateTime;

public record CashChangeSnapshot(
        CashChangeId cashChangeId,
        Name name,
        Description description,
        Money money,
        Type type, CashChangeStatus status,
        ZonedDateTime created,
        ZonedDateTime dueDate,
        ZonedDateTime endDate) {
}
