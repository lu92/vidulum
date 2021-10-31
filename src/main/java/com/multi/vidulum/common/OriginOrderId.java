package com.multi.vidulum.common;

import lombok.Value;

@Value(staticConstructor = "of")
public class OriginOrderId {
    String id;

    public static OriginOrderId notDefined() {
        return new OriginOrderId("NOT_DEFINED");
    }
}
