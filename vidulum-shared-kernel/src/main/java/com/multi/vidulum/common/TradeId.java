package com.multi.vidulum.common;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeId {
    String id;

    public static TradeId of(String id) {
        return new TradeId(id);
    }

}
