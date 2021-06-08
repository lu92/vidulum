package com.multi.vidulum.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderId {
    String id;

    public static OrderId of(String id) {
        return new OrderId(id);
    }
}
