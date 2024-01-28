package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import lombok.AllArgsConstructor;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;

@AllArgsConstructor
public class CashChangeFactory {

    public CashChange empty(
            CashChangeId cashChangeId,
            UserId userId,
            Name name,
            Description description,
            Money money,
            Type type,
            ZonedDateTime created,
            ZonedDateTime dueDate) {


        List<CashFlowEvent> uncommittedEvents = new LinkedList<>();
        uncommittedEvents.add(new CashFlowEvent.CashChangeAppendedEvent(
                cashChangeId,
                userId,
                name,
                description,
                money,
                type,
                created,
                dueDate
        ));
        return CashChange.builder()
                .cashChangeId(cashChangeId)
//                .userId(userId)
                .name(name)
                .description(description)
                .money(money)
                .type(type)
                .status(CashChangeStatus.PENDING)
                .created(created)
                .dueDate(dueDate)
//                .uncommittedEvents(uncommittedEvents)
                .build();
    }
}
