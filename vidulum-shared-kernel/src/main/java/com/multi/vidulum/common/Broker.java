package com.multi.vidulum.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Broker {
    private String Id;

    public static Broker of(String id) {
        return new Broker(id);
    }
}
