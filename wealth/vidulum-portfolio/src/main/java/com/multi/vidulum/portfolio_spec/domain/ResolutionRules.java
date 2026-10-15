package com.multi.vidulum.portfolio_spec.domain;

import com.multi.vidulum.common.CostBasis;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Provenance;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Ticker;

/**
 * Decides which differences a human has to resolve (task D4).
 *
 * <p>Without these rules every difference becomes a question, including an ordinary purchase the
 * exchange already priced — and a mechanism that interrogates the user about their own trades is
 * one they will switch off. The rules exist so the common path runs to completion without anyone
 * being asked anything.
 *
 * <p>The table is §4.2 of the design:
 *
 * <table>
 *   <tr><td>grew, exchange reported a price</td><td>settled</td><td>{@code EXCHANGE_REPORTED}</td></tr>
 *   <tr><td>grew, no price, asset is at par</td><td>settled</td><td>{@code ASSUMED_PAR}</td></tr>
 *   <tr><td>grew, no price, not at par</td><td>ask</td><td>{@code ACQUISITION_COST}</td></tr>
 *   <tr><td>shrank</td><td>ask</td><td>{@code DISPOSAL_REASON}</td></tr>
 * </table>
 *
 * <p><b>One row of §4.2 is deliberately missing.</b> "Shrank, and one of our fills explains it"
 * should settle without asking, but that needs the trade history of the period — and OKX keeps
 * {@code fills-history} for three months, with anything older behind a quarterly archive the POC
 * does not fetch. Until it does, every decrease asks. That is a known cost of a long gap (§5.8),
 * not an oversight.
 */
public final class ResolutionRules {

    private ResolutionRules() {
    }

    /**
     * Classifies units that appeared.
     *
     * @param reportedAvgPrice what the exchange said they cost, or {@code null} if it said nothing
     */
    public static Difference forIncrease(
            Ticker ticker, SubName subName, Quantity quantity, Price reportedAvgPrice) {

        if (reportedAvgPrice != null) {
            return Difference.settled(ticker, subName, DifferenceDirection.INCREASED, quantity,
                    CostBasis.of(quantity, reportedAvgPrice, Provenance.EXCHANGE_REPORTED));
        }
        if (ParAssets.isAtPar(ticker)) {
            return Difference.settled(ticker, subName, DifferenceDirection.INCREASED, quantity,
                    CostBasis.atPar(quantity, ticker.getId()));
        }
        return Difference.asking(ticker, subName, DifferenceDirection.INCREASED, quantity,
                QuestionKind.ACQUISITION_COST);
    }

    /**
     * Cash that appeared, in the currency the portfolio is valued in.
     *
     * <p>Never a question, and not because of {@link ParAssets} — one euro costs one euro by
     * definition when euro is the unit of account. The exchange's {@code openAvgPx} is ignored
     * here for the same reason: a price for the numeraire against itself would say nothing.
     */
    public static Difference forCash(Ticker ticker, Quantity quantity) {
        return Difference.settled(ticker, SubName.none(), DifferenceDirection.INCREASED, quantity,
                CostBasis.atPar(quantity, ticker.getId()));
    }

    /**
     * Classifies units that disappeared.
     *
     * <p>Always a question in the POC — see the note above about fill history. Cash is no
     * exception: money leaving an account is either a withdrawal or a move between the user's own
     * accounts, and only they know which.
     */
    public static Difference forDecrease(Ticker ticker, SubName subName, Quantity quantity) {
        return Difference.asking(ticker, subName, DifferenceDirection.DECREASED, quantity,
                QuestionKind.DISPOSAL_REASON);
    }
}
