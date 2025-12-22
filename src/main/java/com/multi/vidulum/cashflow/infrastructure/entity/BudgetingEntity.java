package com.multi.vidulum.cashflow.infrastructure.entity;

import com.multi.vidulum.common.Money;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.ZonedDateTime;

@Builder
@Getter
@ToString
public class BudgetingEntity {
    private Money budget;
    private ZonedDateTime created;
    private ZonedDateTime lastUpdated;
}
