package com.multi.vidulum.portfolio_spec.domain;

import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Ticker;

import java.util.Objects;

/**
 * One line of what the exchange says an account holds.
 *
 * <p>The shape is dictated by what OKX actually returns: {@code cashBal} (everything held),
 * {@code spotBal} (the part that was traded) and {@code openAvgPx} (the average price of that
 * traded part). Those three numbers map one-to-one onto the split from C2 — which is why the
 * engine never has to guess a single price for a whole balance.
 *
 * @param ticker      what is held
 * @param total       everything held, traded or not
 * @param traded      how much of it was acquired through trades the exchange priced
 * @param frozen      how much of the total the exchange has committed — open orders, pending
 *                    withdrawals (task D5). Summed across Trading and Funding, because both
 *                    accounts report their own {@code frozenBal} and the owner has one balance,
 *                    not two. Cuts across the traded split rather than following it: units are
 *                    fungible, and the exchange freezes a currency, not a provenance.
 * @param reportedAvgPrice average price of the traded part; {@code null} when the exchange
 *                         reported none, which is normal for assets transferred in
 */
public record SnapshotPosition(
        Ticker ticker,
        Quantity total,
        Quantity traded,
        Quantity frozen,
        Price reportedAvgPrice) {

    public SnapshotPosition {
        Objects.requireNonNull(ticker, "ticker is required");
        Objects.requireNonNull(total, "total is required");
        Objects.requireNonNull(traded, "traded is required");
        // Absent means nothing is frozen, which is the honest reading of a venue that reports no
        // such column at all - unlike traded, where absence would be a claim about cost.
        frozen = frozen == null ? Quantity.zero(total.getUnit()) : frozen;
        if (total.isNegative() || traded.isNegative() || frozen.isNegative()) {
            throw new IllegalArgumentException("quantities cannot be negative: " + ticker.getId());
        }
        if (frozen.getQty() > total.getQty()) {
            throw new IllegalArgumentException(String.format(
                    "frozen part [%s] exceeds the total held [%s] for [%s]",
                    frozen, total, ticker.getId()));
        }
        if (traded.getQty() > total.getQty()) {
            throw new IllegalArgumentException(String.format(
                    "traded part [%s] exceeds the total held [%s] for [%s]",
                    traded, total, ticker.getId()));
        }
    }

    /** What the owner can actually act on right now. */
    public Quantity available() {
        return total.minus(frozen);
    }

    /** What arrived from outside: everything the exchange did not price. */
    public Quantity transferredIn() {
        return total.minus(traded);
    }

    public boolean hasReportedPrice() {
        return reportedAvgPrice != null;
    }
}
