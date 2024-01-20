package com.multi.vidulum.cashflow.domain;

import java.util.UUID;

public record CashChangeId(String id) {

    public static CashChangeId generate() {
        return new CashChangeId(UUID.randomUUID().toString());
    }
}
