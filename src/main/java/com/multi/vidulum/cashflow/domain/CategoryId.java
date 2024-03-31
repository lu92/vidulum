package com.multi.vidulum.cashflow.domain;

import java.util.UUID;

public record CategoryId(String id) {

    public static CategoryId generate() {
        return new CategoryId(UUID.randomUUID().toString());
    }
}
