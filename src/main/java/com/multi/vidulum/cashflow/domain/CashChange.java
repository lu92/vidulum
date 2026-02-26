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
    private CategoryName categoryName;
    private CashChangeStatus status;
    private ZonedDateTime created;
    private ZonedDateTime dueDate;
    private ZonedDateTime endDate;
    private String sourceRuleId;

    public CashChangeSnapshot getSnapshot() {
        return new CashChangeSnapshot(
                cashChangeId,
                name,
                description,
                money,
                type,
                categoryName,
                status,
                created,
                dueDate,
                endDate,
                sourceRuleId
        );
    }

    public static CashChange from(CashChangeSnapshot snapshot) {
        return CashChange.builder()
                .cashChangeId(snapshot.cashChangeId())
                .name(snapshot.name())
                .description(snapshot.description())
                .money(snapshot.money())
                .type(snapshot.type())
                .categoryName(snapshot.categoryName())
                .status(snapshot.status())
                .created(snapshot.created())
                .dueDate(snapshot.dueDate())
                .endDate(snapshot.endDate())
                .sourceRuleId(snapshot.sourceRuleId())
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
