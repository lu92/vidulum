package com.multi.vidulum.portfolio.domain.portfolio;

import java.util.Collection;
import java.util.Optional;

/**
 * How much of a value a profit figure actually speaks for (task C4).
 *
 * <p>C3 made every profit honest about <b>which units</b> it refers to: a position whose cost
 * nobody knows reports no profit at all rather than zero. That is necessary and not sufficient.
 * A portfolio holding 147 000 EUR can report a perfectly correct profit of 19 EUR computed over
 * the one position whose cost is known, and a reader shown that number alone will take it for the
 * portfolio's result. The figure is not wrong; it is unrepresentative, and nothing in the payload
 * said so.
 *
 * <p>So every aggregate profit now travels with the share of value it was computed from, and
 * below {@link #MEANINGFUL_FROM} the number is withheld entirely — see {@link ProfitStatus}.
 * Withholding rather than annotating is deliberate: an optional caveat is a caveat that some
 * client will drop, and the misleading number would survive the trip.
 */
public record ProfitCoverage(double share) {

    /**
     * POC value. Half is a judgement, not a derivation — the defensible claim is only that a
     * result computed from a minority of the value does not describe the whole.
     */
    public static final double MEANINGFUL_FROM = 0.5;

    public ProfitCoverage {
        if (share < 0 || share > 1) {
            throw new IllegalArgumentException("coverage must be a share of one, got " + share);
        }
    }

    /**
     * What share of one position has a known cost.
     *
     * <p>Empty when the position holds nothing: there is no denominator, and answering {@code 0}
     * would state "we checked and none of it is covered" about units that do not exist.
     */
    public static Optional<ProfitCoverage> ofPosition(Asset asset) {
        double held = asset.getQuantity().getQty();
        if (held == 0) {
            return Optional.empty();
        }
        return Optional.of(new ProfitCoverage(Math.min(1, asset.coveredQuantity().getQty() / held)));
    }

    /**
     * Coverage of a whole portfolio, weighted by what each position is worth.
     *
     * <p>Weighted and not averaged: a 1% position covered in full and a 99% position covered by
     * nothing is 1% coverage, not 50%. Averaging would flatter exactly the portfolio this exists
     * to warn about.
     */
    public static Optional<ProfitCoverage> weighted(Collection<Weight> weights) {
        double total = weights.stream().mapToDouble(Weight::value).sum();
        if (total == 0) {
            return Optional.empty();
        }
        double covered = weights.stream().mapToDouble(w -> w.value() * w.share()).sum();
        return Optional.of(new ProfitCoverage(Math.min(1, covered / total)));
    }

    /** One position's contribution: what it is worth now, and how much of it has a known cost. */
    public record Weight(double value, double share) {
    }

    public boolean isMeaningful() {
        return share >= MEANINGFUL_FROM;
    }
}
