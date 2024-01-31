package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Reason;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.Aggregate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.multi.vidulum.cashflow.domain.CashChangeStatus.REJECTED;

@Builder
@NoArgsConstructor
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

    public void apply(CashFlowEvent.CashFlowCreatedEvent event) {
        this.cashFlowId = event.cashFlowId();
        this.userId = event.userId();
        this.name = event.name();
        this.description = event.description();
        this.balance = event.balance();
        this.status = CashFlowStatus.OPEN;
        this.cashChanges = new HashMap<>();
        this.created = event.created();
        this.lastModification = null;
        this.uncommittedEvents = new LinkedList<>();
        this.uncommittedEvents.add(event);
    }

    public void apply(CashFlowEvent.CashChangeAppendedEvent event) {
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
        add(event);
    }

    public void apply(CashFlowEvent.CashChangeConfirmedEvent event) {
        performOn(event.cashChangeId(), cashChange -> {
            cashChange.setStatus(CashChangeStatus.CONFIRMED);
            cashChange.setEndDate(event.endDate());
        });
        add(event);
    }

    public void apply(CashFlowEvent.CashChangeEditedEvent event) {
        performOn(event.cashChangeId(), cashChange -> {
            cashChange.setName(event.name());
            cashChange.setDescription(event.description());
            cashChange.setMoney(event.money());
            cashChange.setDueDate(event.dueDate());
        });
        add(event);
    }

    public void apply(CashFlowEvent.CashChangeRejectedEvent event) {
        performOn(event.cashChangeId(), cashChange -> {
            cashChange.onlyWhenIsPending(() -> cashChange.setStatus(REJECTED));
        });
        add(event);
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
