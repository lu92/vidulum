package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.Aggregate;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

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

    public List<CashChangeEvent> getUncommittedEvents() {
        if (Objects.isNull(uncommittedEvents)) {
            uncommittedEvents = new LinkedList<>();
        }
        return uncommittedEvents;
    }
}
