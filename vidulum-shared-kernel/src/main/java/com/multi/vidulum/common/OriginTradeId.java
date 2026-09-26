package com.multi.vidulum.common;

import lombok.*;

import java.util.UUID;

/**
 * What the trade is called <b>at its origin</b>.
 *
 * <p>For a fill, the origin is the exchange and this is its own execution id — the same string a
 * CSV export carries, which is what makes re-importing an overlapping date range harmless.
 *
 * <p>For a trade entered by hand the origin is the client that entered it, so the client mints the
 * value and resends it on a retry: that is what makes a double-click one trade instead of two.
 * When nothing is sent, the backend mints one. That keeps the column free of nulls — so a plain
 * unique index works, with no sparse variant and no "unique unless empty" rule to be surprised by
 * later — but it protects nobody, and is documented as such rather than counted as idempotency.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OriginTradeId {
    String id;

    public static OriginTradeId of(String id) {
        return new OriginTradeId(id);
    }

    /** For a trade whose origin gave it no name. Deliberately unprefixed — see the class note. */
    public static OriginTradeId generate() {
        return new OriginTradeId(UUID.randomUUID().toString());
    }

}
