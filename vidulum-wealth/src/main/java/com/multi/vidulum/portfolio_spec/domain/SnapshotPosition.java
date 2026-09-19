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
 * @param reportedAvgPrice average price of the traded part; {@code null} when the exchange
 *                         reported none, which is normal for assets transferred in
 */
public record SnapshotPosition(
        Ticker ticker,
        Quantity total,
        Quantity traded,
        Price reportedAvgPrice) {

    public SnapshotPosition {
        Objects.requireNonNull(ticker, "ticker is required");
        Objects.requireNonNull(total, "total is required");
        Objects.requireNonNull(traded, "traded is required");
        if (total.isNegative() || traded.isNegative()) {
            throw new IllegalArgumentException("quantities cannot be negative: " + ticker.getId());
        }
        if (traded.getQty() > total.getQty()) {
            throw new IllegalArgumentException(String.format(
                    "traded part [%s] exceeds the total held [%s] for [%s]",
                    traded, total, ticker.getId()));
        }
    }

    /** What arrived from outside: everything the exchange did not price. */
    public Quantity transferredIn() {
        return total.minus(traded);
    }

    public boolean hasReportedPrice() {
        return reportedAvgPrice != null;
    }
}
