package com.multi.vidulum.cashflow.infrastructure.entity;

import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
import com.multi.vidulum.common.Money;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.Optional;

@Builder
@Getter
@ToString
public class CashChangeEntity {

    private String cashChangeId;
    private String name;
    private String description;
    private Money money;
    private Type type;
    private CategoryId categoryId;
    private CashChangeStatus status;
    private Date created;
    private Date dueDate;
    private Date endDate;

    public static CashChangeEntity fromSnapshot(CashChangeSnapshot snapshot) {
        String id = Optional.ofNullable(snapshot.cashChangeId())
                .map(CashChangeId::id).orElse(null);

        Date createdDate = snapshot.created() != null ? Date.from(snapshot.created().toInstant()) : null;
        Date dueDate = snapshot.dueDate() != null ? Date.from(snapshot.dueDate().toInstant()) : null;
        Date endDate = snapshot.endDate() != null ? Date.from(snapshot.endDate().toInstant()) : null;


        return CashChangeEntity.builder()
                .cashChangeId(id)
                .name(snapshot.name().name())
                .description(snapshot.description().description())
                .money(snapshot.money())
                .type(snapshot.type())
                .categoryId(snapshot.categoryId())
                .status(snapshot.status())
                .created(createdDate)
                .dueDate(dueDate)
                .endDate(endDate)
                .build();
    }

    public CashChangeSnapshot toSnapshot() {

        ZonedDateTime createdDateTime = created != null ? ZonedDateTime.ofInstant(created.toInstant(), ZoneOffset.UTC) : null;
        ZonedDateTime dueDateTime = dueDate != null ? ZonedDateTime.ofInstant(dueDate.toInstant(), ZoneOffset.UTC) : null;
        ZonedDateTime endDateTime = endDate != null ? ZonedDateTime.ofInstant(endDate.toInstant(), ZoneOffset.UTC) : null;

        return new CashChangeSnapshot(
                new CashChangeId(cashChangeId),
                new Name(name),
                new Description(description),
                money,
                type,
                categoryId,
                status,
                createdDateTime,
                dueDateTime,
                endDateTime
        );
    }
}
