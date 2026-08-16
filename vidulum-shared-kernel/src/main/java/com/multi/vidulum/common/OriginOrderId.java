package com.multi.vidulum.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OriginOrderId {
    String id;

    public static OriginOrderId of(String id) {
        return new OriginOrderId(id);
    }

    public static OriginOrderId notDefined() {
        return new OriginOrderId("NOT_DEFINED");
    }
}
