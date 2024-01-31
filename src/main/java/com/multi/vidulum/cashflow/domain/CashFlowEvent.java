package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Reason;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.event.DomainEvent;

import java.time.ZonedDateTime;

public sealed interface CashFlowEvent extends DomainEvent
        permits
        CashFlowEvent.CashFlowCreatedEvent,
        CashFlowEvent.CashChangeAppendedEvent,
        CashFlowEvent.CashChangeConfirmedEvent,
        CashFlowEvent.CashChangeEditedEvent,
        CashFlowEvent.CashChangeRejectedEvent {

    record CashFlowCreatedEvent(CashFlowId cashFlowId, UserId userId, Name name, Description description,
                                Money balance, ZonedDateTime created) implements CashFlowEvent {
    }

    record CashChangeAppendedEvent(CashFlowId cashFlowId, CashChangeId cashChangeId, Name name, Description description, Money money,
                                   Type type, ZonedDateTime created, ZonedDateTime dueDate) implements CashFlowEvent {
    }

    record CashChangeConfirmedEvent(CashFlowId cashFlowId, CashChangeId cashChangeId, ZonedDateTime endDate) implements CashFlowEvent {
    }

    record CashChangeEditedEvent(CashFlowId cashFlowId, CashChangeId cashChangeId, Name name, Description description, Money money,
                                 ZonedDateTime dueDate) implements CashFlowEvent {
    }

    record CashChangeRejectedEvent(CashFlowId cashFlowId, CashChangeId cashChangeId, Reason reason) implements CashFlowEvent {
    }
}
