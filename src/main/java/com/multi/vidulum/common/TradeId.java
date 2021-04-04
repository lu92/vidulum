package com.multi.vidulum.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TradeId {
    String id;

    public static TradeId of(String id) {
        return new TradeId(id);
    }

}
