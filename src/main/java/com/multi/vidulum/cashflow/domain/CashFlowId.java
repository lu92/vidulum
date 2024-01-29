package com.multi.vidulum.cashflow.domain;

import java.util.UUID;

public record CashFlowId(String id) {

    public static CashFlowId generate() {
        return new CashFlowId(UUID.randomUUID().toString());
    }
}
