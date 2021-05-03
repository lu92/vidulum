package com.multi.vidulum.common;

import lombok.Value;

@Value
public class OriginTradeId {
    String id;

    public static OriginTradeId of(String id) {
        return new OriginTradeId(id);
    }

}
