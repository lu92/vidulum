package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.event.DomainEvent;

import java.time.ZonedDateTime;

public sealed interface CashChangeEvent extends DomainEvent permits CashChangeEvent.CashChangeConfirmedEvent, CashChangeEvent.CashChangeCreatedEvent {

    record CashChangeCreatedEvent(CashChangeId id, UserId userId, Name name, Description description, Money money,
                                  Type type, ZonedDateTime created, ZonedDateTime dueDate) implements CashChangeEvent {
    }

    record CashChangeConfirmedEvent(CashChangeId id, ZonedDateTime endDate) implements CashChangeEvent {
    }
}
