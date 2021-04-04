package com.multi.vidulum.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Ticker {
    String Id;

    public static Ticker of(String id) {
        return new Ticker(id);
    }
}
