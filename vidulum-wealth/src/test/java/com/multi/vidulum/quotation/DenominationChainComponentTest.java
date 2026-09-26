package com.multi.vidulum.quotation;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.CostBasis;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.PriceOrigin;
import com.multi.vidulum.common.Provenance;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.app.PortfolioFixture;
import com.multi.vidulum.portfolio.app.queries.PortfolioSummaryMapper;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.infrastructure.QuoteRestClientAdapter;
import com.multi.vidulum.quotation.domain.BrokerQuotationProvider;
import com.multi.vidulum.quotation.domain.PriceChangedEvent;
import com.multi.vidulum.quotation.domain.QuotationService;
import com.multi.vidulum.quotation.domain.QuoteNotFoundException;
import com.multi.vidulum.quotation.domain.fx.FxRate;
import com.multi.vidulum.quotation.domain.fx.FxRates;
import com.multi.vidulum.quotation.domain.fx.RateSourceId;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pricing an asset in a currency the exchange does not quote (task B4).
 *
 * <p>The owner thinks in złoty; OKX quotes nothing against it and reports every cost in dollars.
 * Without a route between the two, a portfolio simply has no value in the currency its owner uses
 * — and after the snapshot began covering the funding account, <b>one</b> unpriceable position
 * takes the whole read down with it, because {@code GET /portfolio} prices every asset it holds.
 *
 * <p>Driven through the real {@code QuotationService} and, at the end, the real summary mapper: the
 * arithmetic under test is theirs, and a stubbed quote client would only assert that the stub was
 * asked what the test already told it.
 */
class DenominationChainComponentTest {

    private static final Broker OKX = Broker.of("OKX");
    private static final RateSourceId NBP = RateSourceId.of("NBP");
    private static final Currency PLN = Currency.of("PLN");

    /** Friday's fixing, read on Monday — the ordinary state of a daily rate. */
    private static final ZonedDateTime FRIDAY = ZonedDateTime.parse("2026-09-25T00:00+02:00[Europe/Warsaw]");
    private static final ZonedDateTime MONDAY = ZonedDateTime.parse("2026-09-28T12:00Z");

    private static final class TestProvider extends BrokerQuotationProvider {
        TestProvider(Broker broker) {
            super(broker);
        }
    }

    private final FxRates fxRates = new FxRates();
    private final QuotationService quotationService = new QuotationService(fxRates);

    {
        quotationService.registerBroker(new TestProvider(OKX));
    }

    private void publish(String origin, String destination, double amount, double pctChange, ZonedDateTime at) {
        quotationService.onPriceChange(PriceChangedEvent.builder()
                .broker(OKX)
                .symbol(Symbol.of(Ticker.of(origin), Ticker.of(destination)))
                .currentPrice(Price.of(amount, destination))
                .pctChange(pctChange)
                .dateTime(at)
                .build());
    }

    private AssetPriceMetadata quoteOf(String symbol) {
        return quotationService.fetch(OKX, Symbol.of(symbol));
    }

    // --- the route ---------------------------------------------------------------------------

    /**
     * The case the task exists for: nobody quotes BTC/PLN, and the answer is nevertheless correct —
     * 100 000 USD at 3.6455 złoty is 364 550.
     */
    @Test
    void shouldPriceAnAssetInACurrencyNobodyQuotesItAgainst() {
        publish("BTC", "USD", 100_000, 0.02, MONDAY);
        fxRates.record(FxRate.of("USD", "PLN", 3.6455, FRIDAY, NBP));

        AssetPriceMetadata quote = quoteOf("BTC/PLN");

        assertThat(quote.getCurrentPrice()).isEqualTo(Price.of(364_550, "PLN"));
        assertThat(quote.getSymbol()).isEqualTo(Symbol.of("BTC/PLN"));
        assertThat(quote.getOrigin()).isEqualTo(PriceOrigin.CHAINED);
    }

    /**
     * A chained price is exactly as current as its stalest half. Reporting Monday's moment for a
     * number half of which is Friday's would be the easiest possible lie to tell here, and the one
     * a reader has no way to catch.
     */
    @Test
    void shouldBeAsOldAsItsStalestLeg() {
        publish("BTC", "USD", 100_000, 0.02, MONDAY);
        fxRates.record(FxRate.of("USD", "PLN", 3.6455, FRIDAY, NBP));

        assertThat(quoteOf("BTC/PLN").getDateTime()).isEqualTo(FRIDAY);
    }

    /**
     * The percentage is the asset's movement, taken from the market leg. Folding in the fixing
     * would report a currency revaluation as a market move — and from the number alone nobody
     * could tell which had happened.
     */
    @Test
    void shouldReportTheMovementOfTheAssetNotOfThePair() {
        publish("BTC", "USD", 100_000, 0.02, MONDAY);
        fxRates.record(FxRate.of("USD", "PLN", 3.6455, FRIDAY, NBP));

        assertThat(quoteOf("BTC/PLN").getPctChange()).isEqualTo(0.02);
    }

    /** Dollar cash in a złoty portfolio is the fixing itself — no market leg is involved at all. */
    @Test
    void shouldPriceCashFromTheFixingAlone() {
        fxRates.record(FxRate.of("USD", "PLN", 3.6455, FRIDAY, NBP));

        AssetPriceMetadata quote = quoteOf("USD/PLN");

        assertThat(quote.getCurrentPrice()).isEqualTo(Price.of(3.6455, "PLN"));
        assertThat(quote.getOrigin()).isEqualTo(PriceOrigin.FIXING);
    }

    /**
     * A currency the fixing itself covers is answered by the fixing, not routed through the pivot.
     * Found on the running stack, which held EUR/PLN and never once used it: every route went
     * through the dollar, so valuing euro cash in złoty needed a market quote for EUR/USD that
     * nothing had published — while the exact rate sat in the keeper, unread.
     */
    @Test
    void shouldAnswerACurrencyPairFromTheFixingThatPublishesIt() {
        fxRates.record(FxRate.of("EUR", "PLN", 4.3750, FRIDAY, NBP));

        AssetPriceMetadata quote = quoteOf("EUR/PLN");

        assertThat(quote.getCurrentPrice()).isEqualTo(Price.of(4.3750, "PLN"));
        assertThat(quote.getOrigin()).isEqualTo(PriceOrigin.FIXING);
        assertThat(quote.getDateTime()).isEqualTo(FRIDAY);
        assertThat(quote.getPctChange())
                .as("a fixing states a level, not a move")
                .isZero();
    }

    /** The detour is still there for everything the fixing does not publish outright. */
    @Test
    void shouldStillRouteThroughThePivotForAssetsTheFixingDoesNotCover() {
        publish("BTC", "USD", 100_000, 0, MONDAY);
        fxRates.record(FxRate.of("USD", "PLN", 3.8404, FRIDAY, NBP));

        assertThat(quoteOf("BTC/PLN").getOrigin()).isEqualTo(PriceOrigin.CHAINED);
    }

    /**
     * A price the broker published wins over the fixing too: the market is what one currency
     * actually changes hands at, and the fixing is yesterday's official level.
     */
    @Test
    void shouldPreferAPublishedPriceOverTheFixing() {
        publish("EUR", "PLN", 4.40, 0, MONDAY);
        fxRates.record(FxRate.of("EUR", "PLN", 4.3750, FRIDAY, NBP));

        AssetPriceMetadata quote = quoteOf("EUR/PLN");

        assertThat(quote.getCurrentPrice()).isEqualTo(Price.of(4.40, "PLN"));
        assertThat(quote.getOrigin()).isEqualTo(PriceOrigin.DIRECT);
    }

    /** Recorded backwards, it still answers — the keeper inverts one observation, see FxRates. */
    @Test
    void shouldUseAFixingRecordedInTheOppositeDirection() {
        publish("BTC", "USD", 100_000, 0, MONDAY);
        fxRates.record(FxRate.of("PLN", "USD", 0.25, FRIDAY, NBP));

        assertThat(quoteOf("BTC/PLN").getCurrentPrice()).isEqualTo(Price.of(400_000, "PLN"));
    }

    /**
     * A pair the market actually trades needs no reconstruction, and its own price wins — the chain
     * is a fallback, not a second opinion competing with what was published.
     */
    @Test
    void shouldPreferAPublishedPriceOverTheChain() {
        publish("BTC", "PLN", 370_000, 0, MONDAY);
        publish("BTC", "USD", 100_000, 0, MONDAY);
        fxRates.record(FxRate.of("USD", "PLN", 3.6455, FRIDAY, NBP));

        AssetPriceMetadata quote = quoteOf("BTC/PLN");

        assertThat(quote.getCurrentPrice()).isEqualTo(Price.of(370_000, "PLN"));
        assertThat(quote.getOrigin()).isEqualTo(PriceOrigin.DIRECT);
    }

    // --- where it stops ----------------------------------------------------------------------

    /** No fixing, no route: the refusal is the same one as before the chain existed. */
    @Test
    void shouldStillRefuseWhenNoFixingIsHeld() {
        publish("BTC", "USD", 100_000, 0, MONDAY);

        assertThatThrownBy(() -> quoteOf("BTC/PLN"))
                .isInstanceOf(QuoteNotFoundException.class);
    }

    /**
     * The landmine this task was raised for is only half defused here. A fixing cannot bring back
     * a price the market never published, so a token the exchange does not quote at all still has
     * no value — and, since the read prices every position, still takes the whole portfolio with
     * it. That is the second half of B4: a position without a price has to become a readable gap.
     */
    @Test
    void shouldStillRefuseAnAssetTheMarketNeverQuoted() {
        fxRates.record(FxRate.of("USD", "PLN", 3.6455, FRIDAY, NBP));

        assertThatThrownBy(() -> quoteOf("SOL/PLN"))
                .isInstanceOf(QuoteNotFoundException.class);
    }

    /** Nothing is chained towards the pivot itself: the leg that is missing there is the market one. */
    @Test
    void shouldNotChainWhenTheMissingLegIsTheMarketOne() {
        fxRates.record(FxRate.of("USD", "PLN", 3.6455, FRIDAY, NBP));

        assertThatThrownBy(() -> quoteOf("SOL/USD"))
                .isInstanceOf(QuoteNotFoundException.class);
    }

    // --- what the answer admits to -----------------------------------------------------------

    /** The dollar stablecoin standing in for the dollar says so, rather than passing as published. */
    @Test
    void shouldAdmitWhenAPriceWasSubstituted() {
        publish("BTC", "USDT", 100_000, 0, MONDAY);

        AssetPriceMetadata quote = quoteOf("BTC/USD");

        assertThat(quote.getCurrentPrice()).isEqualTo(Price.of(100_000, "USD"));
        assertThat(quote.getOrigin()).isEqualTo(PriceOrigin.SUBSTITUTED);
    }

    /**
     * A known flattening, pinned so it is a decision rather than a surprise: when a chain is built
     * on a substituted leg, the answer reports the route (chained) and the substitution stops being
     * visible. One field cannot carry both, and the route is the more consequential of the two.
     */
    @Test
    void shouldReportTheRouteWhenTheChainRestsOnASubstitutedLeg() {
        publish("BTC", "USDT", 100_000, 0, MONDAY);
        fxRates.record(FxRate.of("USD", "PLN", 3.6455, FRIDAY, NBP));

        assertThat(quoteOf("BTC/PLN").getOrigin()).isEqualTo(PriceOrigin.CHAINED);
    }

    // --- what the owner actually sees ---------------------------------------------------------

    /**
     * The whole point, read through the real mapper: a portfolio held on an exchange that quotes
     * nothing in złoty, valued in złoty. 1 BTC at 100 000 USD plus 5 000 USD of cash, at 3.6455 —
     * 382 777,50 zł.
     */
    @Test
    void shouldValueAWholePortfolioInTheCurrencyItsOwnerThinksIn() {
        publish("BTC", "USD", 100_000, 0, MONDAY);
        fxRates.record(FxRate.of("USD", "PLN", 3.6455, FRIDAY, NBP));

        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(OKX).denominatedIn("USD")
                .with("BTC", Quantity.of(1),
                        CostBasis.of(Quantity.of(1), Price.of(60_000, "USD"), Provenance.EXCHANGE_REPORTED))
                .withCash("USD", Quantity.of(5_000))
                .contributed(Money.of(50_000, "USD"))
                .build();

        PortfolioDto.PortfolioSummaryJson summary =
                new PortfolioSummaryMapper(new QuoteRestClientAdapter(quotationService)).map(portfolio, PLN);

        assertThat(summary.getCurrentValue()).isEqualTo(Money.of(382_777.50, "PLN"));
        assertThat(summary.getNetContributions())
                .as("what was paid in, restated through the same route")
                .isEqualTo(Money.of(182_275, "PLN"));
    }
}
