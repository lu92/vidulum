package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Reason;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.Aggregate;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.multi.vidulum.cashflow.domain.CashChangeStatus.REJECTED;

@Builder
@AllArgsConstructor
public class CashFlow implements Aggregate<CashFlowId, CashFlowSnapshot> {
    private CashFlowId cashFlowId;
    private UserId userId;
    private Name name;
    private Description description;
    private Money balance;
    private CashFlowStatus status;
    private Map<CashChangeId, CashChange> cashChanges;
    private ZonedDateTime created;
    private ZonedDateTime lastModification;

    private List<CashFlowEvent> uncommittedEvents = new LinkedList<>();


    @Override
    public CashFlowSnapshot getSnapshot() {
        Map<CashChangeId, CashChangeSnapshot> cashChangeSnapshotMap = cashChanges.values().stream()
                .map(cashChange -> new CashChangeSnapshot(
                        cashChange.getCashChangeId(),
                        cashChange.getName(),
                        cashChange.getDescription(),
                        cashChange.getMoney(),
                        cashChange.getType(),
                        cashChange.getStatus(),
                        cashChange.getCreated(),
                        cashChange.getDueDate(),
                        cashChange.getEndDate()
                )).collect(
                        Collectors.toUnmodifiableMap(
                                CashChangeSnapshot::cashChangeId,
                                Function.identity()
                        ));


        return new CashFlowSnapshot(
                cashFlowId,
                userId,
                name,
                description,
                balance,
                status,
                cashChangeSnapshotMap,
                created,
                lastModification
        );
    }

    public static CashFlow from(CashFlowSnapshot snapshot) {

        Map<CashChangeId, CashChange> cashChanges = snapshot.cashChanges().values().stream()
                .map(cashChangeSnapshot -> CashChange.builder()
                        .cashChangeId(cashChangeSnapshot.cashChangeId())
                        .name(cashChangeSnapshot.name())
                        .description(cashChangeSnapshot.description())
                        .money(cashChangeSnapshot.money())
                        .type(cashChangeSnapshot.type())
                        .status(cashChangeSnapshot.status())
                        .created(cashChangeSnapshot.created())
                        .dueDate(cashChangeSnapshot.dueDate())
                        .endDate(cashChangeSnapshot.endDate())
                        .build())
                .collect(Collectors.toMap(
                        CashChange::getCashChangeId,
                        Function.identity()
                ));

        return CashFlow.builder()
                .cashFlowId(snapshot.cashFlowId())
                .userId(snapshot.userId())
                .name(snapshot.name())
                .description(snapshot.description())
                .balance(snapshot.balance())
                .status(snapshot.status())
                .cashChanges(cashChanges)
                .created(snapshot.created())
                .lastModification(snapshot.lastModification())
                .build();
    }

    public void appendCashChange(CashChangeId cashChangeId,
                          Name name,
                          Description description,
                          Money money,
                          Type type,
                          ZonedDateTime created,
                          ZonedDateTime dueDate) {
        CashFlowEvent.CashChangeAppendedEvent event = new CashFlowEvent.CashChangeAppendedEvent(
                cashChangeId,
                userId,
                name,
                description,
                money,
                type,
                created,
                dueDate
        );
        apply(event);
        add(event);
    }

    private void apply(CashFlowEvent.CashChangeAppendedEvent event) {
        CashChange cashChange = new CashChange(
                event.cashChangeId(),
                event.name(),
                event.description(),
                event.money(),
                event.type(),
                CashChangeStatus.PENDING,
                event.created(),
                event.dueDate(),
                null
        );
        cashChanges.put(cashChange.getSnapshot().cashChangeId(), cashChange);
    }

    public void confirm(CashChangeId cashChangeId, ZonedDateTime endDate) {
        CashFlowEvent.CashChangeConfirmedEvent event = new CashFlowEvent.CashChangeConfirmedEvent(cashChangeId, endDate);
        apply(event);
        add(event);
    }

    private void apply(CashFlowEvent.CashChangeConfirmedEvent event) {
        performOn(event.cashChangeId(), cashChange -> {
            cashChange.setStatus(CashChangeStatus.CONFIRMED);
            cashChange.setEndDate(event.endDate());
        });
    }

    public void edit(
            CashChangeId cashChangeId,
            Name name,
            Description description,
            Money money,
            ZonedDateTime dueDate) {
        CashFlowEvent.CashChangeEditedEvent event = new CashFlowEvent.CashChangeEditedEvent(cashChangeId, name, description, money, dueDate);
        apply(event);
        add(event);
    }

    private void apply(CashFlowEvent.CashChangeEditedEvent event) {
        performOn(event.cashChangeId(), cashChange -> {
            cashChange.setName(event.name());
            cashChange.setDescription(event.description());
            cashChange.setMoney(event.money());
            cashChange.setDueDate(event.dueDate());
        });
    }

    public void reject(CashChangeId cashChangeId, Reason reason) {
        CashFlowEvent.CashChangeRejectedEvent event = new CashFlowEvent.CashChangeRejectedEvent(cashChangeId, reason);
        apply(event);
        add(event);
    }

    private void apply(CashFlowEvent.CashChangeRejectedEvent event) {
        performOn(event.cashChangeId(), cashChange -> {
            cashChange.onlyWhenIsPending(() -> cashChange.setStatus(REJECTED));
        });
    }

    private Optional<CashChange> fetchCashChange(CashChangeId cashChangeId) {
        return Optional.ofNullable(cashChanges.getOrDefault(cashChangeId, null));
    }

    private void performOn(CashChangeId cashChangeId, Consumer<CashChange> operation) {
        CashChange cashChange = fetchCashChange(cashChangeId)
                .orElseThrow(() -> new CashChangeDoesNotExistsException(cashChangeId));
        operation.accept(cashChange);
        cashChanges.replace(cashChangeId, cashChange);
    }


    private void add(CashFlowEvent event) {
        // store event temporary
        getUncommittedEvents().add(event);
    }

    public List<CashFlowEvent> getUncommittedEvents() {
        if (Objects.isNull(uncommittedEvents)) {
            uncommittedEvents = new LinkedList<>();
        }
        return uncommittedEvents;
    }

    public enum CashFlowStatus {
        OPEN, CLOSED
    }
}
