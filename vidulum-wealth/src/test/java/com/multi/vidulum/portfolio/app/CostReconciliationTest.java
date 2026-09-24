package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.CostBasis;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Provenance;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.portfolio.domain.portfolio.CostReconciliation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who wins when two readings of the same position disagree — and what happens when the newer one
 * says nothing at all (task D6).
 */
class CostReconciliationTest {

    private static CostBasis cost(double quantity, double price, Provenance provenance) {
        return CostBasis.of(Quantity.of(quantity), Price.of(price, "EUR"), provenance);
    }

    // --- silence -------------------------------------------------------------------------------

    /**
     * The case E10 created. A holding moved into the OKX Funding account comes back with no
     * {@code openAvgPx}, because nothing is traded there. Reading that as "cost unknown" would
     * erase what we already knew, and the owner would watch a portfolio that reported a result
     * yesterday report none today — having done nothing but move a coin between two accounts of
     * the same exchange.
     */
    @Test
    void shouldKeepWhatIsKnownWhenTheNewReadingSaysNothing() {
        CostBasis known = cost(1, 50_000, Provenance.EXCHANGE_REPORTED);

        assertThat(CostReconciliation.afterSynchronisation(known, null)).isEqualTo(known);
    }

    @Test
    void shouldStayEmptyWhenNothingWasKnownAndNothingIsSaid() {
        assertThat(CostReconciliation.afterSynchronisation(null, null)).isNull();
    }

    @Test
    void shouldTakeTheFirstCostItIsEverTold() {
        CostBasis incoming = cost(2, 40_000, Provenance.EXCHANGE_REPORTED);

        assertThat(CostReconciliation.afterSynchronisation(null, incoming)).isEqualTo(incoming);
    }

    // --- absorbing new units -------------------------------------------------------------------

    /**
     * New units are added, not substituted: nothing is in conflict, so the result is the weighted
     * average over both parts.
     */
    @Test
    void shouldAverageOverBothPartsWhenAPositionGrows() {
        CostBasis known = cost(1, 40_000, Provenance.EXCHANGE_REPORTED);
        CostBasis incoming = cost(1, 60_000, Provenance.EXCHANGE_REPORTED);

        CostBasis result = CostReconciliation.afterSynchronisation(known, incoming);

        assertThat(result.quantity()).isEqualTo(Quantity.of(2));
        assertThat(result.avgPrice().getAmount().doubleValue()).isEqualTo(50_000);
    }

    // --- authority -----------------------------------------------------------------------------

    /**
     * A person's own number keeps its protection through the merge. The exchange cannot know what
     * they paid on another venue, so the result must stay the kind of number nothing may overwrite
     * silently — otherwise the next synchronisation quietly finishes the job.
     */
    @Test
    void shouldKeepAUserProvidedCostProtectedAfterAbsorbingAReportedOne() {
        CostBasis known = cost(1, 25_000, Provenance.USER_PROVIDED);
        CostBasis incoming = cost(1, 55_000, Provenance.EXCHANGE_REPORTED);

        CostBasis result = CostReconciliation.afterSynchronisation(known, incoming);

        assertThat(result.provenance()).isEqualTo(Provenance.USER_PROVIDED);
        assertThat(result.isOverwritableSilently()).isFalse();
        assertThat(result.avgPrice().getAmount().doubleValue())
                .as("the user's number is not discarded — it is half of what the position cost")
                .isEqualTo(40_000);
    }

    /**
     * Our own fills are the individual transactions an exchange average is made of, so the result
     * keeps the more precise provenance rather than being relabelled as the exchange's word.
     */
    @Test
    void shouldKeepOurOwnFillsAheadOfAReportedAverage() {
        CostBasis known = cost(2, 30_000, Provenance.DERIVED_FROM_FILLS);
        CostBasis incoming = cost(2, 50_000, Provenance.EXCHANGE_REPORTED);

        assertThat(CostReconciliation.afterSynchronisation(known, incoming).provenance())
                .isEqualTo(Provenance.DERIVED_FROM_FILLS);
    }

    @Test
    void shouldLetAUserCorrectionOutrankOurOwnFills() {
        CostBasis known = cost(1, 30_000, Provenance.DERIVED_FROM_FILLS);
        CostBasis incoming = cost(1, 20_000, Provenance.USER_PROVIDED);

        assertThat(CostReconciliation.afterSynchronisation(known, incoming).provenance())
                .isEqualTo(Provenance.USER_PROVIDED);
    }

    // --- the ranking itself ---------------------------------------------------------------------

    @Test
    void shouldRankSourcesByWhatTheyCanActuallyKnow() {
        assertThat(CostReconciliation.mayReplace(Provenance.EXCHANGE_REPORTED, Provenance.USER_PROVIDED)).isTrue();
        assertThat(CostReconciliation.mayReplace(Provenance.EXCHANGE_REPORTED, Provenance.ASSUMED_PAR)).isTrue();
        assertThat(CostReconciliation.mayReplace(Provenance.EXCHANGE_REPORTED, Provenance.EXCHANGE_REPORTED))
                .as("one reported average may replace another — the later one is simply newer")
                .isTrue();
        assertThat(CostReconciliation.mayReplace(Provenance.USER_PROVIDED, Provenance.EXCHANGE_REPORTED)).isFalse();
        assertThat(CostReconciliation.mayReplace(Provenance.DERIVED_FROM_FILLS, Provenance.EXCHANGE_REPORTED)).isFalse();
        assertThat(CostReconciliation.mayReplace(Provenance.DERIVED_FROM_FILLS, Provenance.USER_PROVIDED)).isTrue();
    }
}
