package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.CostBasis;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.OrderId;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Provenance;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Side;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.TradeId;
import com.multi.vidulum.portfolio.app.queries.PortfolioSummaryMapper;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.portfolio.domain.QuoteRestClient;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.ProfitStatus;
import com.multi.vidulum.portfolio.domain.portfolio.RealisedResult;
import com.multi.vidulum.portfolio.domain.portfolio.RealisedStatus;
import com.multi.vidulum.portfolio.domain.trades.ExecutedTrade;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * What a sale made, once it is closed (task F6) — and what happens when nobody knows (task C7).
 *
 * <p>An owner who bought at 40 000, sold at 60 000 and now holds cash used to see <b>nothing</b>:
 * {@code unrealisedProfit} answers for what is still held, and there is no position left to carry
 * a gain. The figure the old {@code currentValue - investedBalance} carried by accident went out
 * with it when C3 removed the formula.
 *
 * <p>The second half is the rule C7 owns: selling a holding whose cost nobody knows is a real
 * event with a result that cannot be computed. It is recorded as such. Zero would claim the whole
 * proceeds as profit — false, and at settlement time false against the owner.
 */
class RealisedResultComponentTest {

    private static final Broker BROKER = Broker.of("PM");
    private static final Currency EUR = Currency.of("EUR");
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2022-01-01T00:00:00Z");

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

    private static Portfolio holding(String ticker, double quantity, Double knownCostPrice, double cash) {
        PortfolioFixture fixture = PortfolioFixture.portfolio().at(BROKER).denominatedIn("EUR");
        if (knownCostPrice == null) {
            fixture.withUnknownCost(ticker, Quantity.of(quantity));
        } else {
            fixture.with(ticker, Quantity.of(quantity), CostBasis.of(
                    Quantity.of(quantity), Price.of(knownCostPrice, "EUR"), Provenance.DERIVED_FROM_FILLS));
        }
        return fixture.withCash("EUR", Quantity.of(cash)).build();
    }

    private static void sell(Portfolio portfolio, String tradeId, String ticker, SubName subName,
                             double quantity, double price) {
        portfolio.handleExecutedTrade(ExecutedTrade.builder()
                .portfolioId(portfolio.getPortfolioId())
                .tradeId(TradeId.of(tradeId))
                .orderId(OrderId.notDefined())
                .symbol(Symbol.of(Ticker.of(ticker), Ticker.of("EUR")))
                .subName(subName)
                .side(Side.SELL)
                .quantity(Quantity.of(quantity))
                .price(Price.of(price, "EUR"))
                .dateTime(NOW)
                .build());
    }

    private PortfolioDto.PortfolioSummaryJson summaryOf(Portfolio portfolio) {
        // Reading a portfolio values whatever is still held, so anything left over needs a price.
        // The settled result never did — that is the point of the measure.
        prices.putIfAbsent("BTC/EUR", 70_000.0);
        prices.putIfAbsent("XRP/EUR", 2.0);
        return mapper.map(portfolio, EUR);
    }

    // --- the figure that went missing -----------------------------------------------------------

    @Test
    void shouldReportWhatASaleMadeAfterThePositionIsGone() {
        Portfolio portfolio = holding("BTC", 1, 40_000.0, 0);

        sell(portfolio, "trade-1", "BTC", SubName.traded(), 1, 60_000);

        PortfolioDto.PortfolioSummaryJson summary = summaryOf(portfolio);

        assertThat(portfolio.getAssets())
                .as("nothing of it is held any more — which is exactly why it used to vanish")
                .noneSatisfy(asset -> assertThat(asset.getTicker()).isEqualTo(Ticker.of("BTC")));
        assertThat(summary.getRealisedStatus()).isEqualTo(RealisedStatus.COMPUTED);
        assertThat(summary.getRealisedProfit()).isEqualTo(Money.of(20_000, "EUR"));
        assertThat(summary.getRealisedCoverage()).isEqualTo(1.0);
    }

    /** Sold at a loss is a result too, and it is not the same as no result. */
    @Test
    void shouldReportALossAsReadilyAsAGain() {
        Portfolio portfolio = holding("BTC", 1, 40_000.0, 0);

        sell(portfolio, "trade-1", "BTC", SubName.traded(), 0.5, 30_000);

        assertThat(summaryOf(portfolio).getRealisedProfit()).isEqualTo(Money.of(-5_000, "EUR"));
    }

    @Test
    void shouldAddUpEverySaleSoFar() {
        Portfolio portfolio = holding("BTC", 2, 40_000.0, 0);

        sell(portfolio, "trade-1", "BTC", SubName.traded(), 1, 60_000);
        sell(portfolio, "trade-2", "BTC", SubName.traded(), 0.5, 50_000);

        assertThat(summaryOf(portfolio).getRealisedProfit())
                .as("20 000 on the first, 5 000 on the second")
                .isEqualTo(Money.of(25_000, "EUR"));
    }

    @Test
    void shouldSayWhenNothingHasBeenSold() {
        prices.put("BTC/EUR", 70_000.0);
        Portfolio portfolio = holding("BTC", 1, 40_000.0, 0);

        PortfolioDto.PortfolioSummaryJson summary = summaryOf(portfolio);

        assertThat(summary.getRealisedStatus())
                .as("distinct from having sold at no gain, which is a result of zero")
                .isEqualTo(RealisedStatus.NOTHING_SOLD);
        assertThat(summary.getRealisedProfit()).isNull();
    }

    // --- C7: selling what nobody priced ---------------------------------------------------------

    /**
     * The case that made C7 a task of its own: a coin transferred in years ago, sold today. The
     * sale happened; the result did not become computable by happening.
     */
    @Test
    void shouldRecordASaleOfUnpricedUnitsWithoutInventingAResult() {
        Portfolio portfolio = holding("BTC", 1, null, 0);

        sell(portfolio, "trade-1", "BTC", SubName.transferredIn(), 1, 60_000);

        PortfolioDto.PortfolioSummaryJson summary = summaryOf(portfolio);

        assertThat(portfolio.getRealisedResults()).singleElement().satisfies(sale -> {
            assertThat(sale.proceeds())
                    .as("what came in is known even when what it cost is not")
                    .isEqualTo(Money.of(60_000, "EUR"));
            assertThat(sale.cost()).isNull();
            assertThat(sale.covered()).isEqualTo(Quantity.zero("Number"));
        });
        assertThat(summary.getRealisedStatus()).isEqualTo(RealisedStatus.NO_KNOWN_COST);
        assertThat(summary.getRealisedProfit())
                .as("zero here would claim the whole 60 000 as profit — and at settlement, against the owner")
                .isNull();
    }

    /**
     * Sell a position that outgrew what we can price and the result covers the priced part only.
     * That state is ordinary after D6: a synchronisation that reports no cost adds units without
     * adding coverage.
     */
    @Test
    void shouldComputeOverThePricedPartAndSayHowMuchThatWas() {
        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(BROKER).denominatedIn("EUR")
                .at(SubName.traded(), "BTC", Quantity.of(2), CostBasis.of(
                        Quantity.of(1), Price.of(40_000, "EUR"), Provenance.DERIVED_FROM_FILLS))
                .build();

        sell(portfolio, "trade-1", "BTC", SubName.traded(), 2, 60_000);

        PortfolioDto.PortfolioSummaryJson summary = summaryOf(portfolio);

        assertThat(summary.getRealisedProfit())
                .as("one unit of the two had a cost: 60 000 - 40 000")
                .isEqualTo(Money.of(20_000, "EUR"));
        assertThat(summary.getRealisedCoverage()).isCloseTo(0.5, within(1e-9));
        assertThat(summary.getRealisedStatus())
                .as("half is at the threshold, so the figure stands and states its share")
                .isEqualTo(RealisedStatus.COMPUTED);
    }

    /**
     * Weighted by what was sold, not averaged over sales: a fully priced sale of a hundred beside
     * an unpriced one of a hundred thousand is barely covered, and averaging would flatter exactly
     * the portfolio the rule exists to warn about.
     */
    @Test
    void shouldWithholdATotalThatSpeaksForATinyShareOfWhatWasSold() {
        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(BROKER).denominatedIn("EUR")
                .with("XRP", Quantity.of(100), CostBasis.of(
                        Quantity.of(100), Price.of(1, "EUR"), Provenance.DERIVED_FROM_FILLS))
                .withUnknownCost("XRP", Quantity.of(100_000))
                .build();

        sell(portfolio, "trade-1", "XRP", SubName.traded(), 100, 2);
        sell(portfolio, "trade-2", "XRP", SubName.transferredIn(), 100_000, 2);

        PortfolioDto.PortfolioSummaryJson summary = summaryOf(portfolio);

        assertThat(summary.getRealisedStatus()).isEqualTo(RealisedStatus.WITHHELD_LOW_COVERAGE);
        assertThat(summary.getRealisedProfit()).isNull();
        assertThat(summary.getRealisedCoverage()).isCloseTo(100.0 / 100_100, within(1e-6));
    }

    // --- what it is not --------------------------------------------------------------------------

    /**
     * Buying spends cash at par. Nothing is settled by handing over a euro for a euro, and a
     * realised entry there would turn every purchase into a result of zero.
     */
    @Test
    void shouldNotRecordAResultForSpendingCash() {
        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(BROKER).denominatedIn("EUR")
                .withCash("EUR", Quantity.of(100_000))
                .build();

        portfolio.handleExecutedTrade(ExecutedTrade.builder()
                .portfolioId(portfolio.getPortfolioId())
                .tradeId(TradeId.of("trade-1"))
                .orderId(OrderId.notDefined())
                .symbol(Symbol.of(Ticker.of("BTC"), Ticker.of("EUR")))
                .subName(SubName.traded())
                .side(Side.BUY)
                .quantity(Quantity.of(1))
                .price(Price.of(40_000, "EUR"))
                .dateTime(NOW)
                .build());

        assertThat(portfolio.getRealisedResults()).isEmpty();
        assertThat(summaryOf(portfolio).getRealisedStatus()).isEqualTo(RealisedStatus.NOTHING_SOLD);
    }

    /** It answers a different question from the gain on what is still held. */
    @Test
    void shouldStandBesideTheUnrealisedFigureWithoutReplacingIt() {
        prices.put("BTC/EUR", 70_000.0);
        Portfolio portfolio = holding("BTC", 2, 40_000.0, 0);

        sell(portfolio, "trade-1", "BTC", SubName.traded(), 1, 60_000);

        PortfolioDto.PortfolioSummaryJson summary = summaryOf(portfolio);

        assertThat(summary.getRealisedProfit())
                .as("settled: sold one at 60 000 that cost 40 000")
                .isEqualTo(Money.of(20_000, "EUR"));
        assertThat(summary.getUnrealisedProfit().getAmount().doubleValue())
                .as("on paper: the one still held is worth 70 000 and cost 40 000")
                .isEqualTo(30_000);
        assertThat(summary.getProfitStatus()).isEqualTo(ProfitStatus.COMPUTED);
    }

    /**
     * Sell in euro what was priced in dollars.
     *
     * <p>Found on a live backend, not here: the aggregate used to subtract the two directly, and
     * {@code Money.minus} keeps the left operand's currency without looking at the right one — so
     * 2200 EUR minus 2000 USD answered "200 EUR", which then converted into a plausible and wrong
     * number. Both sides are now recorded as they stand and restated together at the read side.
     */
    @Test
    void shouldNotSubtractOneCurrencyFromAnother() {
        prices.put("USD/EUR", 0.9);
        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(BROKER).denominatedIn("EUR")
                .with("XAU", Quantity.of(1), CostBasis.of(
                        Quantity.of(1), Price.of(2_000, "USD"), Provenance.EXCHANGE_REPORTED))
                .build();

        sell(portfolio, "trade-1", "XAU", SubName.traded(), 1, 2_200);

        assertThat(portfolio.getRealisedResults()).singleElement().satisfies(sale -> {
            assertThat(sale.proceeds()).isEqualTo(Money.of(2_200, "EUR"));
            assertThat(sale.cost())
                    .as("kept in the currency it was recorded in, not folded into the proceeds")
                    .isEqualTo(Money.of(2_000, "USD"));
        });

        assertThat(summaryOf(portfolio).getRealisedProfit())
                .as("2 200 EUR less 2 000 USD at 0.9 — 400 EUR, not the 200 the mixed subtraction gave")
                .isEqualTo(Money.of(400, "EUR"));
    }

    /** A sale identifies itself by the trade that produced it — no second id to disagree with. */
    @Test
    void shouldIdentifyEachSaleByItsTrade() {
        Portfolio portfolio = holding("BTC", 1, 40_000.0, 0);

        sell(portfolio, "trade-42", "BTC", SubName.traded(), 1, 60_000);

        assertThat(portfolio.getRealisedResults()).singleElement()
                .extracting(RealisedResult::tradeId, RealisedResult::dateTime)
                .containsExactly(TradeId.of("trade-42"), NOW);
    }
}
