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

    /**
     * A trade that no order preceded — a purchase recorded by hand rather than filled on an
     * exchange. Used instead of {@code null} so the value survives serialisation and comparison
     * without every reader having to guard.
     */
    public static OrderId notDefined() {
        return new OrderId("NOT_DEFINED");
    }

    /** False for {@code notDefined()} — see above. Null-safe on purpose; callers pass what they got. */
    public static boolean isDefined(OrderId orderId) {
        return orderId != null && !notDefined().equals(orderId);
    }
}
