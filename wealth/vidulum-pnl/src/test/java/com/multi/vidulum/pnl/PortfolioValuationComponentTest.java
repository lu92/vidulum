package com.multi.vidulum.pnl;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.pnl.domain.PnlHistory;
import com.multi.vidulum.pnl.domain.PnlId;
import com.multi.vidulum.pnl.domain.PnlPortfolioStatement;
import com.multi.vidulum.pnl.domain.PnlStatement;
import com.multi.vidulum.pnl.domain.PortfolioValuation;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading what a portfolio was worth on a day that has passed (task F9).
 *
 * <p>The reference point every "in this window" measure needs. Without it C5 can only answer since
 * inception, because contributions are dated and holdings are not — asking about a month would
 * mean valuing last month's positions at today's prices, which is the zero {@code investedBalance}
 * in a new disguise.
 */
class PortfolioValuationComponentTest {

    private static final UserId ALICE = UserId.of("U10000001");
    private static final PortfolioId PORTFOLIO = PortfolioId.of("portfolio-1");
    private static final PortfolioId OTHER = PortfolioId.of("portfolio-2");
    private static final ZonedDateTime MONDAY = ZonedDateTime.parse("2022-01-03T01:00:00Z");
    private static final ZonedDateTime TUESDAY = ZonedDateTime.parse("2022-01-04T01:00:00Z");
    private static final ZonedDateTime WEDNESDAY = ZonedDateTime.parse("2022-01-05T01:00:00Z");

    private static PnlStatement statementAt(ZonedDateTime when, PnlPortfolioStatement... portfolios) {
        return PnlStatement.builder()
                .dateTime(when)
                .pnlPortfolioStatements(List.of(portfolios))
                .currentValue(Money.zero("EUR"))
                .build();
    }

    private static PnlPortfolioStatement worth(PortfolioId portfolioId, double value, double contributed) {
        return PnlPortfolioStatement.builder()
                .portfolioId(portfolioId)
                .currentValue(Money.of(value, "EUR"))
                .netContributions(Money.of(contributed, "EUR"))
                .executedTrades(List.of())
                .build();
    }

    private static PnlHistory historyOf(PnlStatement... statements) {
        return PnlHistory.builder()
                .pnlId(PnlId.of("pnl-1"))
                .userId(ALICE)
                .pnlStatements(new ArrayList<>(List.of(statements)))
                .build();
    }

    @Test
    void shouldAnswerWithTheLastValuationTakenBeforeTheMomentAsked() {
        PnlHistory history = historyOf(
                statementAt(MONDAY, worth(PORTFOLIO, 100_000, 90_000)),
                statementAt(TUESDAY, worth(PORTFOLIO, 105_000, 90_000)),
                statementAt(WEDNESDAY, worth(PORTFOLIO, 98_000, 90_000)));

        PortfolioValuation valuation = history.valuationAt(PORTFOLIO, TUESDAY.plusHours(12)).orElseThrow();

        assertThat(valuation.currentValue()).isEqualTo(Money.of(105_000, "EUR"));
        assertThat(valuation.takenAt())
                .as("the moment it was taken, not the moment that was asked for")
                .isEqualTo(TUESDAY);
    }

    /** Never a later one: that would answer the question with information the date did not have. */
    @Test
    void shouldNotReachForwardInTime() {
        PnlHistory history = historyOf(statementAt(WEDNESDAY, worth(PORTFOLIO, 98_000, 90_000)));

        assertThat(history.valuationAt(PORTFOLIO, MONDAY)).isEmpty();
    }

    /**
     * A window that starts before we were watching has no reference point, and saying so is the
     * answer — the measure built on this withholds instead of computing from today's prices.
     */
    @Test
    void shouldSayNothingRatherThanGuessWhenNothingWasRecordedThatEarly() {
        assertThat(historyOf().valuationAt(PORTFOLIO, MONDAY)).isEmpty();
    }

    @Test
    void shouldAnswerAboutTheportfolioAskedAboutAndNoOther() {
        PnlHistory history = historyOf(statementAt(TUESDAY,
                worth(PORTFOLIO, 105_000, 90_000),
                worth(OTHER, 7_000, 7_000)));

        assertThat(history.valuationAt(PORTFOLIO, WEDNESDAY).orElseThrow().currentValue())
                .isEqualTo(Money.of(105_000, "EUR"));
        assertThat(history.valuationAt(OTHER, WEDNESDAY).orElseThrow().currentValue())
                .isEqualTo(Money.of(7_000, "EUR"));
        assertThat(history.valuationAt(PortfolioId.of("portfolio-3"), WEDNESDAY)).isEmpty();
    }

    /** It carries what was put in as well, because a window's change has to net that out (C14). */
    @Test
    void shouldCarryWhatHadBeenPutInByThen() {
        PnlHistory history = historyOf(statementAt(TUESDAY, worth(PORTFOLIO, 105_000, 90_000)));

        assertThat(history.valuationAt(PORTFOLIO, TUESDAY).orElseThrow().netContributions())
                .isEqualTo(Money.of(90_000, "EUR"));
    }

    /** A valuation taken exactly at the moment asked about counts — the boundary is inclusive. */
    @Test
    void shouldCountAValuationTakenAtThatVeryMoment() {
        PnlHistory history = historyOf(statementAt(TUESDAY, worth(PORTFOLIO, 105_000, 90_000)));

        assertThat(history.valuationAt(PORTFOLIO, TUESDAY)).isPresent();
    }
}
