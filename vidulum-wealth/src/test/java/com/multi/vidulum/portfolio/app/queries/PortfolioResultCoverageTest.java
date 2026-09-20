package com.multi.vidulum.portfolio.app.queries;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.CostBasis;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Provenance;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.app.PortfolioFixture;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.portfolio.domain.QuoteRestClient;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.ProfitCoverage;
import com.multi.vidulum.portfolio.domain.portfolio.ProfitStatus;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * What a portfolio's result is allowed to claim (tasks C3 and C4).
 *
 * <p>C3 stopped positions with no known cost from inventing a profit. That fixed the positions and
 * left the total lying: it was {@code currentValue - investedBalance}, and a portfolio built from
 * an exchange snapshot has an {@code investedBalance} of zero, so it reported its entire value as
 * gain. C4 then asks the question a correct total still cannot answer on its own — <b>of how much
 * is this the result?</b> — because a figure computed from 9% of the value is not wrong, it is
 * unrepresentative, and nothing in the payload used to say so.
 *
 * <p>Driven through the real mapper with a stub quote client: the arithmetic under test is the
 * mapper's, and mocking each price individually would bury it.
 */
class PortfolioResultCoverageTest {

    private static final Broker BROKER = Broker.of("OKX");
    private static final Currency EUR = Currency.of("EUR");

    private final Map<String, Double> prices = new HashMap<>();
    private final PortfolioSummaryMapper mapper = new PortfolioSummaryMapper(new QuoteRestClient() {
        @Override
        public AssetPriceMetadata fetch(Broker broker, Symbol symbol) {
            // A currency against itself is one, as the real provider decided in B5 - and the
            // mapper asks for it whenever it denominates investedBalance.
            if (symbol.getOrigin().equals(symbol.getDestination())) {
                return AssetPriceMetadata.builder()
                        .symbol(symbol)
                        .currentPrice(Price.one(symbol.getDestination().getId()))
                        .build();
            }
            Double price = prices.get(symbol.getId());
            if (price == null) {
                throw new AssertionError("test published no price for " + symbol.getId());
            }
            return AssetPriceMetadata.builder()
                    .symbol(symbol)
                    .currentPrice(Price.of(price, symbol.getDestination().getId()))
                    .build();
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

    // --- C3: the total is the positions, not the gap to investedBalance ---------------------

    /**
     * The case that motivated C3, at the size it actually occurred: a portfolio onboarded from an
     * exchange snapshot never passes through a deposit, so {@code investedBalance} is zero and the
     * old formula handed back the whole portfolio as profit.
     */
    @Test
    void shouldNotReportTheWholePortfolioAsProfitWhenNothingWasEverDeposited() {
        price("BTC/EUR", 70_000);
        price("EUR/EUR", 1);

        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(BROKER).denominatedIn("EUR")
                .withUnknownCost("BTC", Quantity.of(2))
                .withCash("EUR", Quantity.of(7_000))
                .build();

        PortfolioDto.PortfolioSummaryJson summary = summaryOf(portfolio);

        assertThat(summary.getInvestedBalance()).isEqualTo(com.multi.vidulum.common.Money.zero("EUR"));
        assertThat(summary.getCurrentValue().getAmount().doubleValue()).isEqualTo(147_000);
        assertThat(summary.getUnrealisedProfit())
                .as("the old formula answered 147 000 here, made entirely out of a missing deposit")
                .isNull();
        assertThat(summary.getPctUnrealisedProfit()).isNull();
    }

    /** Positions whose cost is known carry the total; the rest contribute nothing to it. */
    @Test
    void shouldBuildTheTotalOutOfThePositionsThatHaveACost() {
        price("BTC/EUR", 70_000);
        price("ETH/EUR", 2_000);
        price("EUR/EUR", 1);

        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(BROKER).denominatedIn("EUR")
                .with("BTC", Quantity.of(1), cost(1, 60_000, "EUR"))
                .with("ETH", Quantity.of(10), cost(10, 2_500, "EUR"))
                .withCash("EUR", Quantity.of(5_000))
                .build();

        PortfolioDto.PortfolioSummaryJson summary = summaryOf(portfolio);

        // +10 000 on BTC, -5 000 on ETH, 0 on cash at par.
        assertThat(summary.getUnrealisedProfit().getAmount().doubleValue()).isEqualTo(5_000);
        assertThat(summary.getProfitStatus()).isEqualTo(ProfitStatus.COMPUTED);
        assertThat(summary.getProfitCoverage()).isEqualTo(1.0);
    }

    // --- C4: how much of the value the figure speaks for -------------------------------------

    /**
     * The live portfolio that prompted C4: nine per cent covered. The figure it yields is
     * arithmetically correct and would be read as the portfolio's result, so it is withheld and
     * the coverage says why.
     */
    @Test
    void shouldWithholdAResultComputedFromAMinorityOfTheValue() {
        price("BTC/EUR", 70_000);
        price("EUR/EUR", 1);

        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(BROKER).denominatedIn("EUR")
                .with("BTC", Quantity.of(0.1), cost(0.1, 60_000, "EUR"))
                .withUnknownCost("BTC", Quantity.of(2))
                .build();

        PortfolioDto.PortfolioSummaryJson summary = summaryOf(portfolio);

        assertThat(summary.getProfitStatus()).isEqualTo(ProfitStatus.WITHHELD_LOW_COVERAGE);
        assertThat(summary.getUnrealisedProfit()).isNull();
        assertThat(summary.getPctUnrealisedProfit()).isNull();
        assertThat(summary.getProfitCoverage())
                .as("0.1 of 2.1 BTC, by value")
                .isCloseTo(0.0476, within(1e-4));
    }

    /**
     * Held, and nobody knows what any of it cost. A different silence from the one above, and the
     * status is what tells them apart — {@code null} alone could not.
     */
    @Test
    void shouldSayWhenNoCostIsKnownAtAll() {
        price("BTC/EUR", 70_000);

        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(BROKER).denominatedIn("EUR")
                .withUnknownCost("BTC", Quantity.of(2))
                .build();

        PortfolioDto.PortfolioSummaryJson summary = summaryOf(portfolio);

        assertThat(summary.getProfitStatus()).isEqualTo(ProfitStatus.NO_KNOWN_COST);
        assertThat(summary.getUnrealisedProfit()).isNull();
        assertThat(summary.getProfitCoverage()).isEqualTo(0.0);
    }

    /** And a third silence: nothing is held, so there is nothing to have a cost. */
    @Test
    void shouldSayWhenNothingIsHeld() {
        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(BROKER).denominatedIn("EUR")
                .build();

        PortfolioDto.PortfolioSummaryJson summary = summaryOf(portfolio);

        assertThat(summary.getProfitStatus()).isEqualTo(ProfitStatus.NOTHING_HELD);
        assertThat(summary.getUnrealisedProfit()).isNull();
        assertThat(summary.getProfitCoverage()).isNull();
    }

    /** Above the threshold the figure is published, and still says what it covers. */
    @Test
    void shouldPublishAResultThatCoversMostOfTheValueAndStillReportHowMuch() {
        price("BTC/EUR", 70_000);
        price("EUR/EUR", 1);

        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(BROKER).denominatedIn("EUR")
                .with("BTC", Quantity.of(1), cost(1, 60_000, "EUR"))
                .withCash("EUR", Quantity.of(10_000))
                .build();

        PortfolioDto.PortfolioSummaryJson summary = summaryOf(portfolio);

        assertThat(summary.getProfitStatus()).isEqualTo(ProfitStatus.COMPUTED);
        assertThat(summary.getUnrealisedProfit().getAmount().doubleValue()).isEqualTo(10_000);
        assertThat(summary.getProfitCoverage()).isEqualTo(1.0);
    }

    /** Per position the share is reported but never withheld — see {@code AssetSummaryJson}. */
    @Test
    void shouldReportCoveragePerPositionWithoutWithholdingItsFigure() {
        price("BTC/EUR", 70_000);

        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(BROKER).denominatedIn("EUR")
                .at(com.multi.vidulum.common.SubName.traded(), "BTC", Quantity.of(4),
                        cost(1, 60_000, "EUR"))
                .build();

        List<PortfolioDto.AssetSummaryJson> assets = summaryOf(portfolio).getAssets();

        assertThat(assets).singleElement().satisfies(asset -> {
            assertThat(asset.getCoverage()).isEqualTo(0.25);
            assertThat(asset.getUnrealisedProfit().getAmount().doubleValue())
                    .as("the one unit priced, not all four")
                    .isEqualTo(10_000);
        });
    }

    // --- the rule itself ----------------------------------------------------------------------

    /**
     * Weighted, not averaged. A tiny position covered in full next to a huge one covered by
     * nothing is barely covered — averaging the two shares would answer 50% and flatter exactly
     * the portfolio the rule exists to warn about.
     */
    @Test
    void shouldWeightCoverageByValueRatherThanAverageIt() {
        ProfitCoverage coverage = ProfitCoverage.weighted(List.of(
                new ProfitCoverage.Weight(100, 1.0),
                new ProfitCoverage.Weight(9_900, 0.0))).orElseThrow();

        assertThat(coverage.share()).isCloseTo(0.01, within(1e-9));
        assertThat(coverage.isMeaningful()).isFalse();
    }

    @Test
    void shouldRefuseAShareThatIsNotAShare() {
        assertThatThrownBy(() -> new ProfitCoverage(1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
