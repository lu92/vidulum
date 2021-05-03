package com.multi.vidulum.common;

import lombok.Value;

@Value
public class UserId {
    String id;

    public static UserId of(String id) {
        return new UserId(id);
    }
}
