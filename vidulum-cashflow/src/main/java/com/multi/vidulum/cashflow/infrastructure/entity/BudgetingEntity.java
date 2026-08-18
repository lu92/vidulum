package com.multi.vidulum.cashflow.infrastructure.entity;

import com.multi.vidulum.cashflow.domain.Budgeting;
import com.multi.vidulum.common.Money;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;

@Builder
@Getter
@ToString
public class BudgetingEntity {

    private Money budget;
    private Date created;
    private Date lastUpdated;

    public static BudgetingEntity fromDomain(Budgeting budgeting) {
        if (budgeting == null) {
            return null;
        }

        Date createdDate = budgeting.created() != null ? Date.from(budgeting.created().toInstant()) : null;
        Date lastUpdatedDate = budgeting.lastUpdated() != null ? Date.from(budgeting.lastUpdated().toInstant()) : null;

        return BudgetingEntity.builder()
                .budget(budgeting.budget())
                .created(createdDate)
                .lastUpdated(lastUpdatedDate)
                .build();
    }

    public Budgeting toDomain() {
        ZonedDateTime createdDateTime = created != null ? ZonedDateTime.ofInstant(created.toInstant(), ZoneOffset.UTC) : null;
        ZonedDateTime lastUpdatedDateTime = lastUpdated != null ? ZonedDateTime.ofInstant(lastUpdated.toInstant(), ZoneOffset.UTC) : null;

        return new Budgeting(budget, createdDateTime, lastUpdatedDateTime);
    }
}
