package com.multi.vidulum.common;

import lombok.Value;

import java.util.UUID;

@Value(staticConstructor = "of")
public class MoneyFlowId {
    String Id;

    public static MoneyFlowId generate() {
        return MoneyFlowId.of(UUID.randomUUID().toString());
    }
}
