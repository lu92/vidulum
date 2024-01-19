package com.multi.vidulum.cashflow.infrastructure.entity;

import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.Optional;

@Builder
@Getter
@ToString
@Document("cash-change")
public class CashChangeEntity {

    @Id
    private String cashChangeId;
    private String userId;
    private String name;
    private String description;
    private Money money;
    private Type type;
    private CashChangeStatus status;
    private Date created;
    private Date dueDate;
    private Date endDate;

    public static CashChangeEntity fromSnapshot(CashChangeSnapshot snapshot) {
        String id = Optional.ofNullable(snapshot.cashChangeId())
                .map(CashChangeId::getId).orElse(null);

        Date createdDate = snapshot.created() != null ? Date.from(snapshot.created().toInstant()) : null;
        Date dueDate = snapshot.dueDate() != null ? Date.from(snapshot.dueDate().toInstant()) : null;
        Date endDate = snapshot.endDate() != null ? Date.from(snapshot.endDate().toInstant()) : null;


        return CashChangeEntity.builder()
                .cashChangeId(id)
                .userId(snapshot.userId().getId())
                .name(snapshot.name().name())
                .description(snapshot.description().description())
                .money(snapshot.money())
                .type(snapshot.type())
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
                CashChangeId.of(cashChangeId),
                UserId.of(userId),
                new Name(name),
                new Description(description),
                money,
                type,
                status,
                createdDateTime,
                dueDateTime,
                endDateTime
        );
    }
}
