package com.multi.vidulum.common;

import java.util.Objects;

/**
 * What a position cost, for the part of it we actually know about.
 *
 * <p>Carrying its own {@link #quantity} is the whole point. A plain average price invites the
 * mistake of multiplying the known part's price by the entire balance — hold 100 units, know the
 * cost of 0.3, and the profit is invented. With the quantity inside, the only arithmetic the type
 * offers is over the part it covers.
 *
 * <p>A {@code null} {@code CostBasis} means the cost is unknown. That is the single way to say
 * it: there is no zero price, no {@code Price.one} sentinel and no {@code UNKNOWN} provenance.
 *
 * @param quantity how many units this cost covers — never more than the position holds
 * @param avgPrice average purchase price of exactly that quantity
 * @param provenance where the number came from; decides what may overwrite it
 */
public record CostBasis(
        Quantity quantity,
        Price avgPrice,
        Provenance provenance) {

    public CostBasis {
        Objects.requireNonNull(quantity, "quantity is required");
        Objects.requireNonNull(avgPrice, "avgPrice is required");
        Objects.requireNonNull(provenance, "provenance is required");
        if (quantity.isNegative()) {
            throw new IllegalArgumentException("cost basis quantity cannot be negative: " + quantity);
        }
    }

    public static CostBasis of(Quantity quantity, Price avgPrice, Provenance provenance) {
        return new CostBasis(quantity, avgPrice, provenance);
    }

    /** Cash and stablecoins: one unit cost one unit, and we say so rather than implying it. */
    public static CostBasis atPar(Quantity quantity, String currency) {
        return new CostBasis(quantity, Price.one(currency), Provenance.ASSUMED_PAR);
    }

    /** What the covered part cost in total. */
    public Money totalCost() {
        return avgPrice.multiply(quantity);
    }

    public boolean isOverwritableSilently() {
        return provenance.isOverwritableSilently();
    }

    /**
     * Merges two known costs into their weighted average. Used when a position grows and both
     * the old and the new part have a known cost.
     *
     * <p>The resulting provenance is the <b>weaker</b> of the two — a merge with a user-provided
     * number stays user-provided, so it keeps its protection from silent overwriting.
     */
    public CostBasis merge(CostBasis other) {
        if (!avgPrice.getCurrency().equals(other.avgPrice.getCurrency())) {
            throw new IllegalArgumentException(String.format(
                    "Cannot merge cost basis in [%s] with [%s]",
                    avgPrice.getCurrency(), other.avgPrice.getCurrency()));
        }
        Quantity mergedQuantity = quantity.plus(other.quantity);
        if (mergedQuantity.isZero()) {
            return new CostBasis(mergedQuantity, avgPrice, strongerProtection(other));
        }
        Money mergedCost = totalCost().plus(other.totalCost());
        return new CostBasis(
                mergedQuantity,
                Price.of(mergedCost.divide(mergedQuantity)),
                strongerProtection(other));
    }

    /**
     * Narrows the covered quantity, keeping the average price — what happens when part of a
     * position is sold and the rest keeps the cost it had.
     */
    public CostBasis reduceTo(Quantity remaining) {
        if (remaining.isNegative()) {
            throw new IllegalArgumentException("remaining quantity cannot be negative: " + remaining);
        }
        return new CostBasis(remaining, avgPrice, provenance);
    }

    private Provenance strongerProtection(CostBasis other) {
        return provenance == Provenance.USER_PROVIDED || other.provenance == Provenance.USER_PROVIDED
                ? Provenance.USER_PROVIDED
                : provenance;
    }
}
