package com.multi.vidulum.cashflow.domain;

import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.CashChangeId;
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
    private boolean selfTransfer;

    /**
     * The id this transaction had in the system it came from, or {@code null} for one a person
     * entered by hand.
     *
     * <p>It is what makes importing the same bank statement twice a no-op instead of a double
     * posting. Cashflow had no field for it, so the only thing it could do with a repeated
     * transaction was mint a second {@code CashChangeId} and store it again - and the duplicate
     * check that was supposed to catch that, over in staging, could never fire because nothing ever
     * reported these ids back.
     *
     * <p><b>Enforced in the aggregate, not by an index, and that is not a shortcut.</b> Cash
     * changes are an embedded array inside one {@code cash-flow-document}, and MongoDB's unique
     * constraint applies across documents - a multikey unique index permits repeated values inside
     * a single document's array, so it would be satisfied trivially here and protect nothing. The
     * aggregate already holds every cash change in memory, so {@code CashFlow.findImported} is
     * where the question can actually be answered. A database-level guarantee needs cash changes to
     * become their own collection, which is a separate and much larger change.
     */
    private String bankTransactionId;

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
                sourceRuleId,
                selfTransfer,
                bankTransactionId
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
                .selfTransfer(snapshot.selfTransfer())
                .bankTransactionId(snapshot.bankTransactionId())
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
