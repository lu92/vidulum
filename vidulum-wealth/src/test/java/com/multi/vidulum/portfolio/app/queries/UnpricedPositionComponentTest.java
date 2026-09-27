package com.multi.vidulum.portfolio.app.queries;

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
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.app.PortfolioFixture;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.portfolio.domain.QuoteRestClient;
import com.multi.vidulum.portfolio.domain.portfolio.ContributionStatus;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.ProfitStatus;
import com.multi.vidulum.portfolio.domain.portfolio.ValuationStatus;
import com.multi.vidulum.quotation.domain.QuoteNotFoundException;
import org.junit.jupiter.api.Test;

import com.multi.vidulum.common.Segment;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.portfolio.app.AggregatedPortfolio;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A position nobody can price is a gap in the portfolio, not a failed read (task C16).
 *
 * <p>{@code GET /portfolio} prices every asset it holds, so one missing quote used to throw and
 * the owner received a 404 where a portfolio should have been. That became reachable when the
 * snapshot started covering the funding account — where anything ever deposited sits, tokens the
 * exchange does not quote included — and B4's currency chain cannot reach it: no exchange rate
 * brings back a price the market never published.
 *
 * <p>Driven through the real mapper with a quote client that refuses what was not published, which
 * is what the live one does.
 */
class UnpricedPositionComponentTest {

    private static final Broker OKX = Broker.of("OKX");
    private static final Currency EUR = Currency.of("EUR");

    private final Map<String, Double> prices = new HashMap<>();
    private final PortfolioSummaryMapper mapper = new PortfolioSummaryMapper(new QuoteRestClient() {
        @Override
        public AssetPriceMetadata fetch(Broker broker, Symbol symbol) {
            if (symbol.getOrigin().equals(symbol.getDestination())) {
                return AssetPriceMetadata.builder()
                        .symbol(symbol)
                        .currentPrice(Price.one(symbol.getDestination().getId()))
                        .build();
            }
            Double price = prices.get(symbol.getId());
            if (price == null) {
                throw new QuoteNotFoundException(symbol);
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

    private static CostBasis cost(double quantity, double price, String currency) {
        return CostBasis.of(Quantity.of(quantity), Price.of(price, currency), Provenance.EXCHANGE_REPORTED);
    }

    private PortfolioDto.AssetSummaryJson rowOf(PortfolioDto.PortfolioSummaryJson summary, String ticker) {
        return summary.getAssets().stream()
                .filter(asset -> asset.getTicker().equals(ticker))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + ticker));
    }

    /** One token from the funding account that the exchange does not quote, beside two that it does. */
    private Portfolio portfolioWithOneUnpriceablePosition() {
        price("BTC/EUR", 70_000);
        price("EUR/EUR", 1);
        return PortfolioFixture.portfolio()
                .at(OKX).denominatedIn("EUR")
                .with("BTC", Quantity.of(1), cost(1, 60_000, "EUR"))
                .withCash("EUR", Quantity.of(5_000))
                .withUnknownCost("SOL", Quantity.of(12))
                .contributed(Money.of(50_000, "EUR"))
                .build();
    }

    // --- the row -------------------------------------------------------------------------------

    /**
     * The position is still there. Everything the portfolio knows without a market — quantity,
     * locks, origin — is reported; only price and value are silent.
     */
    @Test
    void shouldCarryAPositionNobodyCanPrice() {
        PortfolioDto.PortfolioSummaryJson summary =
                mapper.map(portfolioWithOneUnpriceablePosition(), EUR);

        PortfolioDto.AssetSummaryJson sol = rowOf(summary, "SOL");
        assertThat(sol.getQuantity()).isEqualTo(Quantity.of(12));
        assertThat(sol.getSubName()).isEqualTo("transferred-in");
        assertThat(sol.getCurrentPrice()).isNull();
        assertThat(sol.getCurrentValue()).isNull();
    }

    /**
     * And it says why it is silent. {@code null} alone reads as a field nobody filled in — the
     * lesson C15 paid for, when two BTC rows arrived with no way to tell them apart.
     */
    @Test
    void shouldSayThatThePriceIsUnknownRatherThanLeaveItToNull() {
        PortfolioDto.PortfolioSummaryJson summary =
                mapper.map(portfolioWithOneUnpriceablePosition(), EUR);

        assertThat(rowOf(summary, "SOL").isPriceUnknown()).isTrue();
        assertThat(rowOf(summary, "BTC").isPriceUnknown()).isFalse();
    }

    /** The rest of the portfolio is priced exactly as before — the gap costs one row, not the read. */
    @Test
    void shouldPriceEveryOtherPositionAsUsual() {
        PortfolioDto.PortfolioSummaryJson summary =
                mapper.map(portfolioWithOneUnpriceablePosition(), EUR);

        assertThat(rowOf(summary, "BTC").getCurrentValue()).isEqualTo(Money.of(70_000, "EUR"));
        assertThat(rowOf(summary, "EUR").getCurrentValue()).isEqualTo(Money.of(5_000, "EUR"));
    }

    // --- the total -----------------------------------------------------------------------------

    /**
     * The total covers what could be priced and says so, naming the positions it does not include.
     * No share accompanies it, unlike every other partial figure here: coverage is weighted by
     * value, and the value of a position nobody can price is the unknown itself — a percentage
     * would have to invent the quantity it claims to measure.
     */
    @Test
    void shouldTotalWhatCouldBePricedAndNameWhatCouldNot() {
        PortfolioDto.PortfolioSummaryJson summary =
                mapper.map(portfolioWithOneUnpriceablePosition(), EUR);

        assertThat(summary.getCurrentValue()).isEqualTo(Money.of(75_000, "EUR"));
        assertThat(summary.getValuationStatus()).isEqualTo(ValuationStatus.PARTIAL);
        assertThat(summary.getUnpricedAssets()).containsExactly("SOL/transferred-in");
    }

    /**
     * The change in wealth goes. It subtracts a complete ledger from an incomplete value, so it is
     * understated by however much the missing position is worth — an amount nobody can state,
     * which is why the figure leaves rather than carries a caveat.
     */
    @Test
    void shouldWithholdTheChangeInWealthWhileSomethingIsUnpriced() {
        PortfolioDto.PortfolioSummaryJson summary =
                mapper.map(portfolioWithOneUnpriceablePosition(), EUR);

        assertThat(summary.getNetContributions())
                .as("what was paid in is still known — only the value side is incomplete")
                .isEqualTo(Money.of(50_000, "EUR"));
        assertThat(summary.getContributionStatus()).isEqualTo(ContributionStatus.COMPUTED);
        assertThat(summary.getWealthChange()).isNull();
        assertThat(summary.getPctWealthChange()).isNull();
    }

    /** Same argument one level down, and the status says which silence this is. */
    @Test
    void shouldWithholdTheResultWithAStatusThatNamesTheReason() {
        PortfolioDto.PortfolioSummaryJson summary =
                mapper.map(portfolioWithOneUnpriceablePosition(), EUR);

        assertThat(summary.getUnrealisedProfit()).isNull();
        assertThat(summary.getPctUnrealisedProfit()).isNull();
        assertThat(summary.getProfitStatus()).isEqualTo(ProfitStatus.WITHHELD_UNPRICED_POSITIONS);
        assertThat(summary.getProfitCoverage())
                .as("no share can be given: the missing value is what cannot be measured")
                .isNull();
    }

    // --- the other three states ------------------------------------------------------------------

    @Test
    void shouldSayEverythingWasPricedWhenItWas() {
        price("BTC/EUR", 70_000);
        price("EUR/EUR", 1);
        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(OKX).denominatedIn("EUR")
                .with("BTC", Quantity.of(1), cost(1, 60_000, "EUR"))
                .withCash("EUR", Quantity.of(5_000))
                .contributed(Money.of(50_000, "EUR"))
                .build();

        PortfolioDto.PortfolioSummaryJson summary = mapper.map(portfolio, EUR);

        assertThat(summary.getValuationStatus()).isEqualTo(ValuationStatus.COMPLETE);
        assertThat(summary.getUnpricedAssets()).isEmpty();
        assertThat(summary.getCurrentValue()).isEqualTo(Money.of(75_000, "EUR"));
        assertThat(summary.getWealthChange())
                .as("nothing missing, so the comparison is sound again")
                .isEqualTo(Money.of(25_000, "EUR"));
    }

    /**
     * Nothing priced answers {@code null}, not zero. A portfolio of coins nobody quotes is not a
     * portfolio worth nothing, and every interface renders zero as a fact.
     */
    @Test
    void shouldNotReportZeroWhenNothingCouldBePriced() {
        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(OKX).denominatedIn("EUR")
                .withUnknownCost("SOL", Quantity.of(12))
                .build();

        PortfolioDto.PortfolioSummaryJson summary = mapper.map(portfolio, EUR);

        assertThat(summary.getValuationStatus()).isEqualTo(ValuationStatus.NOTHING_PRICED);
        assertThat(summary.getCurrentValue()).isNull();
        assertThat(summary.getUnpricedAssets()).containsExactly("SOL/transferred-in");
    }

    /** An empty portfolio is worth zero, and that is a different claim from the one above. */
    @Test
    void shouldStillReportZeroForAPortfolioThatHoldsNothing() {
        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(OKX).denominatedIn("EUR")
                .build();

        PortfolioDto.PortfolioSummaryJson summary = mapper.map(portfolio, EUR);

        assertThat(summary.getValuationStatus()).isEqualTo(ValuationStatus.NOTHING_HELD);
        assertThat(summary.getCurrentValue()).isEqualTo(Money.zero("EUR"));
        assertThat(summary.getUnpricedAssets()).isEmpty();
    }

    // --- across portfolios -----------------------------------------------------------------------

    /**
     * The aggregate is where this mattered most and was least visible: it sums positions from
     * <b>every</b> portfolio the owner has, so one unquotable token in one of them used to blank
     * the view of all the others. Now it costs that row its value and the total says so.
     */
    @Test
    void shouldNotLetOneUnpriceablePositionBlankTheWholeAggregate() {
        price("BTC/EUR", 70_000);

        AggregatedPortfolio.GroupedAssets crypto = AggregatedPortfolio.GroupedAssets.builder().build();
        crypto.appendAsset(OKX, Asset.builder()
                .ticker(Ticker.of("BTC"))
                .subName(SubName.traded())
                .costBasis(cost(1, 60_000, "EUR"))
                .quantity(Quantity.of(1))
                .locked(Quantity.zero())
                .free(Quantity.of(1))
                .activeLocks(Set.of())
                .build());
        crypto.appendAsset(OKX, Asset.builder()
                .ticker(Ticker.of("SOL"))
                .subName(SubName.transferredIn())
                .costBasis(null)
                .quantity(Quantity.of(12))
                .locked(Quantity.zero())
                .free(Quantity.of(12))
                .activeLocks(Set.of())
                .build());

        AggregatedPortfolio aggregated = AggregatedPortfolio.builder()
                .userId(com.multi.vidulum.common.UserId.of("U10000001"))
                .segmentedAssets(Map.of(Segment.of("Crypto"), crypto))
                .portfolioIds(List.of(PortfolioId.of("portfolio-1")))
                .portfolioContributions(List.of())
                .build();

        PortfolioDto.AggregatedPortfolioSummaryJson summary = mapper.map(aggregated, EUR);

        assertThat(summary.getCurrentValue()).isEqualTo(Money.of(70_000, "EUR"));
        assertThat(summary.getValuationStatus()).isEqualTo(ValuationStatus.PARTIAL);
        assertThat(summary.getUnpricedAssets()).containsExactly("SOL/transferred-in");
    }

    /** What is known without a market survives: the cost basis is reported on the unpriced row too. */
    @Test
    void shouldKeepReportingWhatIsKnownWithoutAMarket() {
        price("BTC/EUR", 70_000);
        price("EUR/EUR", 1);
        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(OKX).denominatedIn("EUR")
                .with("SOL", Quantity.of(12), cost(12, 100, "EUR"))
                .with("BTC", Quantity.of(1), cost(1, 60_000, "EUR"))
                .build();

        PortfolioDto.AssetSummaryJson sol = rowOf(mapper.map(portfolio, EUR), "SOL");

        assertThat(sol.getCostBasis().getAvgPrice()).isEqualTo(Price.of(100, "EUR"));
        assertThat(sol.getCurrentValue()).isNull();
        assertThat(sol.getUnrealisedProfit())
                .as("a cost with nothing to compare it against yields no result")
                .isNull();
    }
}
