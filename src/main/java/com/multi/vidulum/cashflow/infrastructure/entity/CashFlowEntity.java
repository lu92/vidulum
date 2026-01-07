package com.multi.vidulum.cashflow.infrastructure.entity;

import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.common.Checksum;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.UserId;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.YearMonth;
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
    private List<CategoryEntity> inflowCategories;
    private List<CategoryEntity> outflowCategories;
    private String startPeriod;
    private String activePeriod;
    private Money initialBalance;
    private Date created;
    private Date lastModification;
    private Date importCutoffDateTime;
    private String lastMessageChecksum;

    public static CashFlowEntity fromSnapshot(CashFlowSnapshot snapshot) {
        String id = Optional.ofNullable(snapshot.cashFlowId())
                .map(CashFlowId::id).orElse(null);

        Date createdDate = snapshot.created() != null ? Date.from(snapshot.created().toInstant()) : null;
        Date lastModification = snapshot.lastModification() != null ? Date.from(snapshot.lastModification().toInstant()) : null;
        String lastMessageChecksum = snapshot.lastMessageChecksum() != null ? snapshot.lastMessageChecksum().checksum() : null;
        List<CashChangeEntity> cashChangeEntities = snapshot.cashChanges().values().stream()
                .map(CashChangeEntity::fromSnapshot)
                .collect(Collectors.toList());

        Date importCutoffDateTime = snapshot.importCutoffDateTime() != null
                ? Date.from(snapshot.importCutoffDateTime().toInstant()) : null;

        return CashFlowEntity.builder()
                .cashFlowId(id)
                .userId(snapshot.userId().getId())
                .name(snapshot.name().name())
                .description(snapshot.description().description())
                .bankAccount(snapshot.bankAccount())
                .status(snapshot.status())
                .cashChanges(cashChangeEntities)
                .inflowCategories(CategoryEntity.fromDomainList(snapshot.inflowCategories()))
                .outflowCategories(CategoryEntity.fromDomainList(snapshot.outflowCategories()))
                .startPeriod(snapshot.startPeriod() != null ? snapshot.startPeriod().toString() : null)
                .activePeriod(snapshot.activePeriod().toString())
                .initialBalance(snapshot.initialBalance())
                .created(createdDate)
                .lastModification(lastModification)
                .importCutoffDateTime(importCutoffDateTime)
                .lastMessageChecksum(lastMessageChecksum)
                .build();
    }

    public CashFlowSnapshot toSnapshot() {

        ZonedDateTime createdDateTime = ZonedDateTime.ofInstant(created.toInstant(), ZoneOffset.UTC);
        ZonedDateTime lastModificationDateTime = lastModification != null ? ZonedDateTime.ofInstant(lastModification.toInstant(), ZoneOffset.UTC) : null;
        ZonedDateTime importCutoffDateTimeValue = importCutoffDateTime != null ? ZonedDateTime.ofInstant(importCutoffDateTime.toInstant(), ZoneOffset.UTC) : null;
        Checksum checksumValue = lastMessageChecksum != null ? new Checksum(lastMessageChecksum) : null;

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
                startPeriod != null ? YearMonth.parse(startPeriod) : YearMonth.parse(activePeriod),
                YearMonth.parse(activePeriod),
                initialBalance,
                CategoryEntity.toDomainList(inflowCategories),
                CategoryEntity.toDomainList(outflowCategories),
                createdDateTime,
                lastModificationDateTime,
                importCutoffDateTimeValue,
                checksumValue
        );
    }
}
