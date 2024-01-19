package com.multi.vidulum.cashflow.domain;

import lombok.Value;

import java.util.UUID;

@Value(staticConstructor = "of")
public class CashChangeId {
    String id;

    public static CashChangeId generate() {
        return CashChangeId.of(UUID.randomUUID().toString());
    }
}
