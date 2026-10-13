package com.multi.vidulum.portfolio.domain.portfolio;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.TradeId;

import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * What one sale actually made, once it was closed (task F6).
 *
 * <p>The portfolio reports a gain on what it <b>still holds</b> ({@code unrealisedProfit}, C3), so
 * an owner who bought at 40 000, sold at 60 000 and now sits on cash sees nothing at all: there is
 * no position left to have a gain. The old {@code currentValue - investedBalance} carried that
 * number by accident, and removing it — correctly — removed this with it.
 *
 * <p>It is not the same as the change in wealth (C5). Wealth mixes what was closed with what is
 * still open; this is only the part that is settled, which is also the only part a tax office
 * recognises.
 *
 * <p><b>The result may be absent, and absent is never zero (task C7).</b> Selling units whose cost
 * nobody knows is a real event with an uncomputable result — a bitcoin transferred in years ago
 * and sold today. Recording zero there would claim the whole proceeds were profit, which is both
 * false and, at settlement time, against the owner.
 *
 * @param tradeId  the trade this came from; identity rather than a generated id, because a sale
 *                 already has one and inventing a second would let them disagree
 * @param covered  how many of the sold units had a known cost — may be fewer than
 *                 {@code quantity}, which is what makes the result partial rather than absent
 * @param cost     what the covered units cost, <b>in the currency that cost was recorded in</b>;
 *                 {@code null} when none of them had one. Kept beside the proceeds rather than
 *                 subtracted from them, because the two can be in different currencies — sell in
 *                 euro what an exchange priced in dollars and the difference is not a number the
 *                 aggregate can compute. It reads as one: {@code Money.minus} keeps the left
 *                 operand's currency and subtracts the amounts, so 2200 EUR minus 2000 USD
 *                 answered "200 EUR". Plausible, and wrong by the exchange rate. The subtraction
 *                 belongs where the rates are, which is the read side (C9 made the same choice
 *                 for contributions).
 */
public record RealisedResult(
        TradeId tradeId,
        ZonedDateTime dateTime,
        Ticker ticker,
        SubName subName,
        Quantity quantity,
        Quantity covered,
        Money proceeds,
        Money cost) {

    public RealisedResult {
        Objects.requireNonNull(tradeId, "tradeId is required");
        Objects.requireNonNull(dateTime, "dateTime is required");
        Objects.requireNonNull(ticker, "ticker is required");
        Objects.requireNonNull(subName, "subName is required");
        Objects.requireNonNull(quantity, "quantity is required");
        Objects.requireNonNull(covered, "covered is required");
        Objects.requireNonNull(proceeds, "proceeds is required");
        if (covered.getQty() > quantity.getQty() + 1e-9) {
            throw new IllegalArgumentException(
                    "more units priced than sold for " + ticker.getId());
        }
        if (covered.isZero() && cost != null) {
            throw new IllegalArgumentException(
                    "a sale of units with no known cost cannot state one: " + ticker.getId());
        }
        if (!covered.isZero() && cost == null) {
            throw new IllegalArgumentException(
                    "a sale of priced units must say what they cost: " + ticker.getId());
        }
    }

    /** Whether a result can be computed from this sale at all — see {@code cost}. */
    public boolean isComputable() {
        return cost != null;
    }

    /** What share of the sale this result speaks for — the C4 question, one sale at a time. */
    public double coverageShare() {
        return quantity.isZero() ? 0 : covered.getQty() / quantity.getQty();
    }
}
