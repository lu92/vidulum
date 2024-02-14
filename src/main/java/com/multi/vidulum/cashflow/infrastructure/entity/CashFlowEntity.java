package com.multi.vidulum.cashflow.infrastructure.entity;

import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Builder
@Getter
@ToString
@Document("cash-flow-document")
public class CashFlowEntity {

    @Id
    private String cashFlowId;
    private String userId;
    private String name;
    private String description;
    private BankAccount bankAccount;
    private CashFlow.CashFlowStatus status;
    private List<CashChangeEntity> cashChanges;
    private Date created;
    private Date lastModification;

    public static CashFlowEntity fromSnapshot(CashFlowSnapshot snapshot) {
        String id = Optional.ofNullable(snapshot.cashFlowId())
                .map(CashFlowId::id).orElse(null);

        Date createdDate = snapshot.created() != null ? Date.from(snapshot.created().toInstant()) : null;
        Date lastModification = snapshot.lastModification() != null ? Date.from(snapshot.lastModification().toInstant()) : null;
        List<CashChangeEntity> cashChangeEntities = snapshot.cashChanges().values().stream()
                .map(CashChangeEntity::fromSnapshot)
                .collect(Collectors.toList());

        return CashFlowEntity.builder()
                .cashFlowId(id)
                .userId(snapshot.userId().getId())
                .name(snapshot.name().name())
                .description(snapshot.description().description())
                .bankAccount(snapshot.bankAccount())
                .status(snapshot.status())
                .cashChanges(cashChangeEntities)
                .created(createdDate)
                .lastModification(lastModification)
                .build();
    }

    public CashFlowSnapshot toSnapshot() {

        ZonedDateTime createdDateTime = ZonedDateTime.ofInstant(created.toInstant(), ZoneOffset.UTC);
        ZonedDateTime lastModificationDateTime = lastModification != null ? ZonedDateTime.ofInstant(lastModification.toInstant(), ZoneOffset.UTC) : null;


        Map<CashChangeId, CashChangeSnapshot> cashChangeSnapshotMap = cashChanges.stream()
                .map(CashChangeEntity::toSnapshot)
                .collect(Collectors.toUnmodifiableMap(
                        CashChangeSnapshot::cashChangeId,
                        Function.identity()));

        return new CashFlowSnapshot(
                new CashFlowId(cashFlowId),
                UserId.of(userId),
                new Name(name),
                new Description(description),
                bankAccount,
                status,
                cashChangeSnapshotMap,
                createdDateTime,
                lastModificationDateTime
        );
    }
}
