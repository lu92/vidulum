package com.multi.vidulum.cashflow_forecast_processor.app;

import com.multi.vidulum.common.Money;

import java.time.ZonedDateTime;

public record Attestation(
        Money bankAccountBalance,
        Type type,
        ZonedDateTime dateTime) {

    public enum Type {
        MANUAL, AUTO
    }
}
