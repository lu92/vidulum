package com.multi.vidulum.portfolio.domain.portfolio;

import com.multi.vidulum.common.CostBasis;
import com.multi.vidulum.common.Provenance;

/**
 * What happens to a position's cost when a synchronisation brings a new reading (task D6).
 *
 * <p>Two rules, and they answer different questions.
 *
 * <p><b>Silence is not a statement.</b> A synchronisation that reports no cost has not said the
 * cost is unknown — it has said nothing. The case is ordinary rather than exotic: after E10 a
 * holding moved into the OKX Funding account comes back with no {@code openAvgPx} at all, because
 * nothing is traded there. Reading that as "no cost" would erase what we already knew, and the
 * owner would watch a portfolio that reported a result yesterday report none today, having done
 * nothing but move a coin between two accounts of the same exchange. So: <i>a synchronisation
 * that sees less than the last one does not get to forget.</i>
 *
 * <p><b>Authority decides who wins when both speak.</b> A cost a person typed outranks anything an
 * exchange reports, because the exchange cannot know what they paid elsewhere. Our own fills
 * outrank a reported average, because they are the individual transactions that average is made
 * of. Everything else is interchangeable. The ranking lives on {@link Provenance#authority()}
 * where the values are, not here.
 *
 * <p>New units are <b>absorbed</b>, never overwritten: a position that grows by units with a known
 * cost keeps a weighted average over both parts. That is not a conflict — nothing is being
 * replaced — which is why the ranking only decides the provenance the result carries, and with it
 * how the next synchronisation may treat it.
 */
public final class CostReconciliation {

    private CostReconciliation() {
    }

    /**
     * The cost a position should carry after a synchronisation added {@code incoming} units.
     *
     * @param known    what we hold now; {@code null} when nothing was ever known
     * @param incoming the cost of the units this synchronisation adds; {@code null} when the
     *                 exchange said nothing about them
     */
    public static CostBasis afterSynchronisation(CostBasis known, CostBasis incoming) {
        if (incoming == null) {
            return known;
        }
        if (known == null) {
            return incoming;
        }
        CostBasis merged = known.merge(incoming);
        return merged.provenance() == strongerOf(known.provenance(), incoming.provenance())
                ? merged
                : CostBasis.of(merged.quantity(), merged.avgPrice(),
                        strongerOf(known.provenance(), incoming.provenance()));
    }

    /**
     * Whether a reading from {@code incoming} may replace one from {@code known} outright — the
     * question a restatement asks, as opposed to the addition {@link #afterSynchronisation}
     * handles.
     */
    public static boolean mayReplace(Provenance known, Provenance incoming) {
        return known.yieldsTo(incoming);
    }

    private static Provenance strongerOf(Provenance left, Provenance right) {
        return left.authority() >= right.authority() ? left : right;
    }
}
