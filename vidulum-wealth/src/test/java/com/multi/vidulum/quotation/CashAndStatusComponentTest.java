package com.multi.vidulum.quotation;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.quotation.app.ExchangeStatusDto;
import com.multi.vidulum.quotation.app.ExchangeStatusRestController;
import com.multi.vidulum.quotation.app.Reachability;
import com.multi.vidulum.quotation.domain.BrokerQuotationProvider;
import com.multi.vidulum.quotation.domain.PriceChangedEvent;
import com.multi.vidulum.quotation.domain.QuotationService;
import com.multi.vidulum.quotation.domain.QuoteNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Quotes for cash (task B5) and the readiness endpoint that guards onboarding (task B6).
 *
 * <p>Both exist because of the same failure: {@code GET /portfolio} prices <b>every</b> asset it
 * holds, cash included, and a missing quote throws. B5 removes the one quote nobody would think
 * to publish; B6 lets the caller check the rest are there before a portfolio exists, instead of
 * discovering it afterwards with a portfolio that cannot be valued.
 */
class CashAndStatusComponentTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2022-01-01T00:00:00Z");
    private static final Broker OKX = Broker.of("OKX");
    private static final Broker BINANCE = Broker.of("BINANCE");

    private final Clock clock = Clock.fixed(Instant.parse("2022-01-01T00:00:00Z"), ZoneOffset.UTC);

    /** Concrete subclass, because the real providers add nothing beyond an identity. */
    private static final class TestProvider extends BrokerQuotationProvider {
        TestProvider(Broker broker) {
            super(broker);
        }
    }

    private QuotationService quotationService;
    private ExchangeStatusRestController controller;

    @BeforeEach
    void setUp() {
        quotationService = new QuotationService();
        quotationService.registerBroker(new TestProvider(OKX));
        controller = new ExchangeStatusRestController(quotationService, clock);
    }

    private void publish(Broker broker, String origin, String destination, double amount) {
        quotationService.onPriceChange(PriceChangedEvent.builder()
                .broker(broker)
                .symbol(Symbol.of(Ticker.of(origin), Ticker.of(destination)))
                .currentPrice(Price.of(amount, destination))
                .pctChange(0)
                .dateTime(NOW)
                .build());
    }

    // --- B5 ---------------------------------------------------------------------------------

    /**
     * The quote nobody would think to publish. Cash in a portfolio valued in the same currency is
     * the symbol EUR/EUR, and without this every such portfolio fails to load.
     */
    @ParameterizedTest
    @ValueSource(strings = {"EUR", "USD", "PLN", "BTC"})
    void shouldPriceAnyAssetAgainstItselfAtOne(String ticker) {
        AssetPriceMetadata quote = quotationService.fetch(
                OKX, Symbol.of(Ticker.of(ticker), Ticker.of(ticker)));

        assertThat(quote.getCurrentPrice()).isEqualTo(Price.one(ticker));
        assertThat(quote.getPctChange()).isZero();
        assertThat(quote.getSymbol()).isEqualTo(Symbol.of(Ticker.of(ticker), Ticker.of(ticker)));
    }

    /**
     * Identity is arithmetic, not market data, so a published value must not be able to
     * contradict it — otherwise one bad publish quietly rewrites what a euro is worth in euros.
     */
    @Test
    void shouldIgnoreAPublishedPriceThatContradictsIdentity() {
        publish(OKX, "EUR", "EUR", 0.93);

        assertThat(quotationService.fetch(OKX, Symbol.of(Ticker.of("EUR"), Ticker.of("EUR")))
                .getCurrentPrice())
                .isEqualTo(Price.one("EUR"));
    }

    @Test
    void shouldStillRequireARealQuoteForOneCurrencyAgainstAnother() {
        assertThatThrownBy(() -> quotationService.fetch(
                OKX, Symbol.of(Ticker.of("USD"), Ticker.of("EUR"))))
                .isInstanceOf(QuoteNotFoundException.class);

        publish(OKX, "USD", "EUR", 0.93);

        assertThat(quotationService.fetch(OKX, Symbol.of(Ticker.of("USD"), Ticker.of("EUR")))
                .getCurrentPrice())
                .isEqualTo(Price.of(0.93, "EUR"));
    }

    @Test
    void shouldNotCountIdentityPairsAsSomethingThatWasPublished() {
        quotationService.fetch(OKX, Symbol.of(Ticker.of("EUR"), Ticker.of("EUR")));

        assertThat(quotationService.quotedSymbols(OKX))
                .as("an identity quote is computed, never cached")
                .isEmpty();
    }

    // --- B6 ---------------------------------------------------------------------------------

    @Test
    void shouldReportAnExchangeWithNoQuotesAsNotReady() {
        ExchangeStatusDto.ExchangeStatusJson status = controller.one("OKX");

        assertThat(status.brokerRegistered()).isTrue();
        assertThat(status.quotesReady()).isFalse();
        assertThat(status.quotedSymbols()).isEmpty();
        assertThat(status.checkedAt()).isEqualTo(NOW);
    }

    @Test
    void shouldListWhatCanBePricedOnceQuotesArrive() {
        publish(OKX, "BTC", "EUR", 50_000);
        publish(OKX, "USDC", "EUR", 0.93);

        ExchangeStatusDto.ExchangeStatusJson status = controller.one("OKX");

        assertThat(status.quotesReady()).isTrue();
        assertThat(status.quotedSymbols()).containsExactly("BTC/EUR", "USDC/EUR");
    }

    /**
     * "We do not serve that exchange" is the useful answer to "can I use it". A 404 would leave
     * the caller guessing whether the endpoint itself was wrong.
     */
    @Test
    void shouldAnswerForAnExchangeItDoesNotServeInsteadOfFailing() {
        ExchangeStatusDto.ExchangeStatusJson status = controller.one("BINANCE");

        assertThat(status.exchange()).isEqualTo("BINANCE");
        assertThat(status.brokerRegistered()).isFalse();
        assertThat(status.quotesReady()).isFalse();
        assertThat(status.message()).contains("No quotation provider");
    }

    /**
     * Reachability and readiness are different questions, and the endpoint must not answer the
     * second while appearing to answer the first. Saying ONLINE because our own cache is warm
     * would be believed.
     */
    @Test
    void shouldNotClaimTheExchangeIsReachableWhileNothingProbesIt() {
        publish(OKX, "BTC", "EUR", 50_000);

        ExchangeStatusDto.ExchangeStatusJson status = controller.one("OKX");

        assertThat(status.reachability()).isEqualTo(Reachability.UNKNOWN.name());
        assertThat(status.quotesReady())
                .as("readiness is known even when reachability is not")
                .isTrue();
        assertThat(status.message()).contains("not probed yet");
    }

    @Test
    void shouldListEveryRegisteredExchange() {
        quotationService.registerBroker(new TestProvider(BINANCE));
        publish(BINANCE, "BTC", "USD", 51_000);

        ExchangeStatusDto.ExchangeStatusListJson all = controller.all();

        assertThat(all.exchanges()).extracting(ExchangeStatusDto.ExchangeStatusJson::exchange)
                .containsExactly("BINANCE", "OKX");
        assertThat(all.exchanges())
                .extracting(ExchangeStatusDto.ExchangeStatusJson::quotesReady)
                .containsExactly(true, false);
    }

    /**
     * The check the prototype actually performs before onboarding: every symbol the portfolio
     * will need, priced, with the identity pair not needing to appear at all.
     */
    @Test
    void shouldLetTheCallerVerifyEverySymbolAPortfolioWillNeed() {
        List.of("BTC", "ETH", "XRP", "USD", "USDC")
                .forEach(ticker -> publish(OKX, ticker, "EUR", 1));

        ExchangeStatusDto.ExchangeStatusJson status = controller.one("OKX");

        assertThat(status.quotedSymbols())
                .containsExactly("BTC/EUR", "ETH/EUR", "USD/EUR", "USDC/EUR", "XRP/EUR");
        assertThat(quotationService.fetch(OKX, Symbol.of(Ticker.of("EUR"), Ticker.of("EUR"))))
                .as("the sixth quote needs no publishing")
                .isNotNull();
    }
}
