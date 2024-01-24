package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Reason;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.Aggregate;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

import static com.multi.vidulum.cashflow.domain.CashChangeStatus.*;

@Builder
@AllArgsConstructor
public class CashChange implements Aggregate<CashChangeId, CashChangeSnapshot> {

    private CashChangeId cashChangeId;
    private UserId userId;
    private Name name;
    private Description description;
    private Money money;
    private Type type;
    private CashChangeStatus status;
    private ZonedDateTime created;
    private ZonedDateTime dueDate;
    private ZonedDateTime endDate;
    private List<CashChangeEvent> uncommittedEvents;

    @Override
    public CashChangeSnapshot getSnapshot() {
        return new CashChangeSnapshot(
                cashChangeId,
                userId,
                name,
                description,
                money,
                type,
                status,
                created,
                dueDate,
                endDate
        );
    }

    public static CashChange from(CashChangeSnapshot snapshot) {
        return CashChange.builder()
                .cashChangeId(snapshot.cashChangeId())
                .userId(snapshot.userId())
                .name(snapshot.name())
                .description(snapshot.description())
                .money(snapshot.money())
                .type(snapshot.type())
                .status(snapshot.status())
                .created(snapshot.created())
                .dueDate(snapshot.dueDate())
                .endDate(snapshot.endDate())
                .build();
    }

    public void confirm(ZonedDateTime endDate) {
        CashChangeEvent.CashChangeConfirmedEvent event = new CashChangeEvent.CashChangeConfirmedEvent(cashChangeId, endDate);
        apply(event);
        add(event);
    }

    private void apply(CashChangeEvent.CashChangeConfirmedEvent event) {
        whenIsPending(() -> {
            status = CONFIRMED;
            endDate = event.endDate();
        });
    }

    public void edit(
            Name name,
            Description description,
            Money money,
            ZonedDateTime dueDate) {
        CashChangeEvent.CashChangeEditedEvent event = new CashChangeEvent.CashChangeEditedEvent(cashChangeId, name, description, money, dueDate);
        apply(event);
        add(event);
    }

    private void apply(CashChangeEvent.CashChangeEditedEvent event) {
        whenIsPending(() -> {
            name = event.name();
            description = event.description();
            money = event.money();
            dueDate = event.dueDate();
        });
    }

    public void reject(Reason reason) {
        CashChangeEvent.CashChangeRejectedEvent event = new CashChangeEvent.CashChangeRejectedEvent(cashChangeId, reason);
        apply(event);
        add(event);
    }

    private void apply(CashChangeEvent.CashChangeRejectedEvent event) {
        whenIsPending(() -> status = REJECTED);
    }

    private void whenIsPending(Runnable action) {
        if (isPending()) {
            action.run();
        } else throw new CashChangeIsNotOpenedException(type, cashChangeId);
    }

    private boolean isPending() {
        return PENDING.equals(status);
    }

    private void add(CashChangeEvent event) {
        // store event temporary
        getUncommittedEvents().add(event);
    }

    public List<CashChangeEvent> getUncommittedEvents() {
        if (Objects.isNull(uncommittedEvents)) {
            uncommittedEvents = new LinkedList<>();
        }
        return uncommittedEvents;
    }
}
