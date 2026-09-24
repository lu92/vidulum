package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.CostBasis;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Provenance;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.portfolio.app.queries.PortfolioSummaryMapper;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.portfolio.domain.QuoteRestClient;
import com.multi.vidulum.portfolio.domain.portfolio.ContributionStatus;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.ProfitStatus;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * How much the owner's wealth has changed since they started (task C5).
 *
 * <p>The measure exists because the one next to it cannot answer for most of a real portfolio.
 * {@code unrealisedProfit} needs a purchase price, and on the OKX account this was written against
 * 92% of the value arrived from somewhere else with no price attached — so the result is withheld
 * and the owner is told nothing. This measure needs only what the portfolio is worth and what went
 * into it, both of which we have for every position, priced or not.
 *
 * <p>It is a different question, not a fallback: wealth can grow while every decision lagged the
 * market. The two figures stand side by side and are never added together.
 */
class WealthChangeComponentTest {

    private static final Broker BROKER = Broker.of("OKX");
    private static final Currency EUR = Currency.of("EUR");

    private final Map<String, Double> prices = new HashMap<>();
    private final PortfolioSummaryMapper mapper = new PortfolioSummaryMapper(new QuoteRestClient() {
        @Override
        public AssetPriceMetadata fetch(Broker broker, Symbol symbol) {
            if (symbol.getOrigin().equals(symbol.getDestination())) {
                return AssetPriceMetadata.builder()
                        .symbol(symbol).currentPrice(Price.one(symbol.getDestination().getId())).build();
            }
            Double price = prices.get(symbol.getId());
            if (price == null) {
                throw new AssertionError("test published no price for " + symbol.getId());
            }
            return AssetPriceMetadata.builder()
                    .symbol(symbol).currentPrice(Price.of(price, symbol.getDestination().getId())).build();
        }

        @Override
        public AssetBasicInfo fetchBasicInfoAboutAsset(Broker broker, Ticker ticker) {
            return AssetBasicInfo.notFound(ticker);
        }

        @Override
        public void registerBasicInfoAboutAsset(Broker broker, AssetBasicInfo assetBasicInfo) {
        }
    });

    private void price(String symbol, double amount) {
        prices.put(symbol, amount);
    }

    private PortfolioDto.PortfolioSummaryJson summaryOf(Portfolio portfolio) {
        return mapper.map(portfolio, EUR);
    }

    private static CostBasis cost(double quantity, double price, String currency) {
        return CostBasis.of(Quantity.of(quantity), Price.of(price, currency), Provenance.EXCHANGE_REPORTED);
    }

    // --- the case the measure exists for ------------------------------------------------------

    /**
     * The shape of the live account: everything held came in from elsewhere, so no result can be
     * computed — and the owner still learns how much their wealth grew.
     */
    @Test
    void shouldAnswerForAPortfolioWhoseCostIsEntirelyUnknown() {
        price("BTC/EUR", 70_000);

        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(BROKER).denominatedIn("EUR")
                .withUnknownCost("BTC", Quantity.of(2))
                .contributed(Money.of(100_000, "EUR"))
                .build();

        PortfolioDto.PortfolioSummaryJson summary = summaryOf(portfolio);

        assertThat(summary.getProfitStatus())
                .as("nothing held has a known cost, so the result stays silent")
                .isEqualTo(ProfitStatus.NO_KNOWN_COST);
        assertThat(summary.getUnrealisedProfit()).isNull();

        assertThat(summary.getWealthChange())
                .as("140 000 held against 100 000 put in — the unpriced coin counts in full")
                .isEqualTo(Money.of(40_000, "EUR"));
        assertThat(summary.getPctWealthChange()).isCloseTo(0.4, within(1e-9));
    }

    @Test
    void shouldBeTheValueLessWhatWentIn() {
        price("BTC/EUR", 60_000);

        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(BROKER).denominatedIn("EUR")
                .with("BTC", Quantity.of(0.2), cost(0.2, 50_000, "EUR"))
                .contributed(Money.of(10_000, "EUR"))
                .build();

        PortfolioDto.PortfolioSummaryJson summary = summaryOf(portfolio);

        assertThat(summary.getCurrentValue().getAmount().doubleValue()).isEqualTo(12_000);
        assertThat(summary.getWealthChange()).isEqualTo(Money.of(2_000, "EUR"));
        assertThat(summary.getPctWealthChange()).isCloseTo(0.2, within(1e-9));
    }

    /**
     * A deposit is not a gain. Without the ledger these two would be indistinguishable — which is
     * why C5 could not be written before C9 existed.
     */
    @Test
    void shouldNotCountAFreshDepositAsGrowth() {
        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(BROKER).denominatedIn("EUR")
                .withCash("EUR", Quantity.of(10_000))
                .contributed(Money.of(10_000, "EUR"))
                .build();

        PortfolioDto.PortfolioSummaryJson summary = summaryOf(portfolio);

        assertThat(summary.getWealthChange())
                .as("the money is theirs and always was")
                .isEqualTo(Money.of(0, "EUR"));
        assertThat(summary.getPctWealthChange()).isEqualTo(0.0);
    }

    /** A portfolio onboarded a moment ago has grown by nothing, and says so rather than staying silent. */
    @Test
    void shouldReportNoChangeForAPortfolioJustOnboarded() {
        price("BTC/EUR", 50_000);

        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(BROKER).denominatedIn("EUR")
                .withUnknownCost("BTC", Quantity.of(1))
                .contributed(Money.of(50_000, "EUR"))
                .build();

        assertThat(summaryOf(portfolio).getWealthChange()).isEqualTo(Money.of(0, "EUR"));
    }

    @Test
    void shouldFallWhenTheMarketFalls() {
        price("BTC/EUR", 40_000);

        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(BROKER).denominatedIn("EUR")
                .withUnknownCost("BTC", Quantity.of(1))
                .contributed(Money.of(50_000, "EUR"))
                .build();

        PortfolioDto.PortfolioSummaryJson summary = summaryOf(portfolio);

        assertThat(summary.getWealthChange()).isEqualTo(Money.of(-10_000, "EUR"));
        assertThat(summary.getPctWealthChange()).isCloseTo(-0.2, within(1e-9));
    }

    // --- what the ledger does to it ------------------------------------------------------------

    /** Money taken back out lowers what was put in, so what remains is measured against less. */
    @Test
    void shouldMeasureAgainstContributionsNetOfWithdrawals() {
        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(BROKER).denominatedIn("EUR")
                .withCash("EUR", Quantity.of(5_000))
                .contributed(Money.of(10_000, "EUR"))
                .withdrawn(Money.of(6_000, "EUR"))
                .build();

        PortfolioDto.PortfolioSummaryJson summary = summaryOf(portfolio);

        assertThat(summary.getNetContributions()).isEqualTo(Money.of(4_000, "EUR"));
        assertThat(summary.getWealthChange())
                .as("5 000 left, 4 000 of it their own")
                .isEqualTo(Money.of(1_000, "EUR"));
    }

    /**
     * Take back out everything you put in and the change is still a number — but there is nothing
     * left to express it as a share of. A percentage against zero is not small, it is undefined.
     */
    @Test
    void shouldWithholdThePercentageWhenNothingIsLeftToDivideBy() {
        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(BROKER).denominatedIn("EUR")
                .withCash("EUR", Quantity.of(3_000))
                .contributed(Money.of(10_000, "EUR"))
                .withdrawn(Money.of(10_000, "EUR"))
                .build();

        PortfolioDto.PortfolioSummaryJson summary = summaryOf(portfolio);

        assertThat(summary.getWealthChange()).isEqualTo(Money.of(3_000, "EUR"));
        assertThat(summary.getPctWealthChange()).isNull();
    }

    /** Derived from the ledger, so it falls silent with it — and the ledger's status says why. */
    @Test
    void shouldStaySilentWhenTheLedgerIsWithheld() {
        price("BTC/EUR", 70_000);

        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(BROKER).denominatedIn("EUR")
                .withUnknownCost("BTC", Quantity.of(2))
                .contributed(Money.of(1_000, "EUR"))
                .contributedOfUnknownValue(Money.of(1, "BTC"))
                .contributedOfUnknownValue(Money.of(2, "ETH"))
                .build();

        PortfolioDto.PortfolioSummaryJson summary = summaryOf(portfolio);

        assertThat(summary.getContributionStatus()).isEqualTo(ContributionStatus.WITHHELD_LOW_COVERAGE);
        assertThat(summary.getWealthChange())
                .as("a change measured against a third of the ledger describes neither")
                .isNull();
        assertThat(summary.getPctWealthChange()).isNull();
    }

    @Test
    void shouldStaySilentWhenNothingWasEverPutIn() {
        price("BTC/EUR", 70_000);

        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(BROKER).denominatedIn("EUR")
                .withUnknownCost("BTC", Quantity.of(1))
                .build();

        PortfolioDto.PortfolioSummaryJson summary = summaryOf(portfolio);

        assertThat(summary.getContributionStatus()).isEqualTo(ContributionStatus.NOTHING_CONTRIBUTED);
        assertThat(summary.getWealthChange())
                .as("70 000 of growth out of nowhere is exactly the lie C3 removed")
                .isNull();
    }

    /** Both sides are restated in the currency asked for before they meet. */
    @Test
    void shouldComputeInWhicheverCurrencyIsAskedFor() {
        price("USD/EUR", 0.9);
        price("BTC/EUR", 45_000);

        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(BROKER).denominatedIn("USD")
                .withUnknownCost("BTC", Quantity.of(1))
                .contributed(Money.of(40_000, "USD"))
                .build();

        PortfolioDto.PortfolioSummaryJson summary = summaryOf(portfolio);

        assertThat(summary.getNetContributions())
                .as("40 000 USD stated in euro")
                .isEqualTo(Money.of(36_000, "EUR"));
        assertThat(summary.getWealthChange()).isEqualTo(Money.of(9_000, "EUR"));
    }
}
