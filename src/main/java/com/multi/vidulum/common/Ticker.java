package com.multi.vidulum.common;

import lombok.Value;

@Value
public class Ticker {
    String Id;

    public static Ticker of(String id) {
        return new Ticker(id);
    }
}
