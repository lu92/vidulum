package com.multi.vidulum.common;

import lombok.Value;

@Value
public class PositionId {
    String id;

    public static PositionId of(String id) {
        return new PositionId(id);
    }

}
