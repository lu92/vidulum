package com.multi.vidulum.common;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OriginTradeId {
    String id;

    public static OriginTradeId of(String id) {
        return new OriginTradeId(id);
    }

}
