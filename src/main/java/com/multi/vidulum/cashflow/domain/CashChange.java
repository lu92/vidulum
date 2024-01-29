package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
import com.multi.vidulum.common.Money;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.ZonedDateTime;

import static com.multi.vidulum.cashflow.domain.CashChangeStatus.PENDING;

@Data
@Builder
@AllArgsConstructor
public class CashChange {

    private CashChangeId cashChangeId;
    private Name name;
    private Description description;
    private Money money;
    private Type type;
    private CashChangeStatus status;
    private ZonedDateTime created;
    private ZonedDateTime dueDate;
    private ZonedDateTime endDate;

    public CashChangeSnapshot getSnapshot() {
        return new CashChangeSnapshot(
                cashChangeId,
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

    public void onlyWhenIsPending(Runnable action) {
        if (isPending()) {
            action.run();
        } else throw new CashChangeIsNotOpenedException(type, cashChangeId);
    }

    private boolean isPending() {
        return PENDING.equals(status);
    }
}
