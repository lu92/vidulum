package com.multi.vidulum.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderId {
    String id;

    public static OrderId of(String id) {
        return new OrderId(id);
    }

    public static OrderId generate() {
        return OrderId.of(UUID.randomUUID().toString());
    }

    public static OrderId notDefined() {
        return new OrderId("NOT_DEFINED");
    }
}
