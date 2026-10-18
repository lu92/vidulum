package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.common.Money;

import java.time.ZonedDateTime;

public record Budgeting(
        Money budget,
        ZonedDateTime created,
        ZonedDateTime lastUpdated
) {
    public Budgeting withUpdatedBudget(Money newBudget, ZonedDateTime updatedAt) {
        return new Budgeting(newBudget, this.created, updatedAt);
    }
}
