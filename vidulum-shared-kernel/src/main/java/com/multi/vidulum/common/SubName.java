package com.multi.vidulum.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class SubName {
    private String name;

    public static SubName of(String subName) {
        return new SubName(subName);
    }

    /** Cash. Money has no origin to split, and its cost is always at par. */
    public static SubName none() {
        return new SubName("");
    }

    /**
     * Acquired through a trade we recorded, so the cost is known from the fills.
     *
     * <p>One half of the split described in C2. Keeping the two origins as separate positions is
     * what lets every position be wholly known or wholly unknown, instead of one row carrying a
     * cost that covers only part of what it holds.
     */
    public static SubName traded() {
        return new SubName("traded");
    }

    /**
     * Arrived from outside — a transfer in, an airdrop, a gift. The cost is unknown until the
     * user supplies it, and inventing one would turn a guess into reported profit.
     */
    public static SubName transferredIn() {
        return new SubName("transferred-in");
    }

    public boolean isCash() {
        return none().equals(this);
    }
}
