package com.multi.vidulum.common;

import lombok.Value;

@Value
public class TradeId {
    String id;

    public static TradeId of(String id) {
        return new TradeId(id);
    }

}
