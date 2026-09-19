package com.multi.vidulum.okx;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.common.Segment;
import com.multi.vidulum.quotation.domain.BrokerNotFoundException;
import com.multi.vidulum.quotation.domain.PriceChangedEvent;
import com.multi.vidulum.quotation.domain.QuotationService;
import com.multi.vidulum.quotation.domain.QuoteNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Component test: real collaborators, no Spring context, no containers - the same level as
 * {@code PortfolioTest} in vidulum-wealth. Nothing needs stubbing here because this slice holds
 * no infrastructure; the cache is in memory by nature.
 *
 * <p>Wiring is NOT covered here - whether the bean from {@code OkxQuotationConfiguration} reaches
 * {@code List<BrokerQuotationProvider>} is proven by {@code OkxQuotationEndpointTest}.
 *
 * <p>Exercised through {@link QuotationService} rather than the provider directly: the interesting
 * methods on {@code BrokerQuotationProvider} - {@code onPriceChange} and {@code fetch} - are
 * package-private to {@code com.multi.vidulum.quotation.domain} and unreachable from here.
 * The service's equivalents are public and delegate to them, so this is the only seam that
 * covers caching at all.
 */
class OkxQuotationComponentTest {

    private static final Broker OKX = Broker.of("OKX");
    private static final ZonedDateTime FIXED_NOW = ZonedDateTime.parse("2022-01-01T00:00:00Z[UTC]");

    private QuotationService quotationService;
    private OkxBrokerQuotationProvider provider;

    @BeforeEach
    void setUp() {
        provider = new OkxBrokerQuotationProvider();
        quotationService = new QuotationService();
        quotationService.registerBroker(provider);
    }

    private static PriceChangedEvent priceOf(String origin, String destination, double amount, String currency) {
        return PriceChangedEvent.builder()
                .broker(OKX)
                .symbol(Symbol.of(Ticker.of(origin), Ticker.of(destination)))
                .currentPrice(Price.of(amount, currency))
                .pctChange(0.0)
                .dateTime(FIXED_NOW)
                .build();
    }

    // ---------- rejestracja i rozpoznanie brokera ----------

    @Test
    void shouldReportOkxAsItsBroker() {
        assertThat(provider.getBroker()).isEqualTo(Broker.of("OKX"));
    }

    @Test
    void shouldNoLongerRejectOkxAsUnknownBroker() {
        // regresja: bez zarejestrowanego providera kazde wywolanie dla OKX konczylo sie
        // BrokerNotFoundException - to jest dokladnie ta zmiana, ktora wprowadza ten modul
        quotationService.onPriceChange(priceOf("BTC", "EUR", 66532.9, "EUR"));

        assertThatCode(() -> quotationService.fetch(OKX, Symbol.of(Ticker.of("BTC"), Ticker.of("EUR"))))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldStillRejectBrokersThatWereNeverRegistered() {
        assertThatThrownBy(() -> quotationService.fetch(Broker.of("KRAKEN"), Symbol.of(Ticker.of("BTC"), Ticker.of("EUR"))))
                .isInstanceOf(BrokerNotFoundException.class);
    }

    @Test
    void shouldKeepTheFirstProviderWhenTheSameBrokerIsRegisteredTwice() {
        // registerBroker uzywa putIfAbsent; podmiana instancji wyrzucilaby caly cache cen
        quotationService.onPriceChange(priceOf("BTC", "EUR", 66532.9, "EUR"));
        quotationService.registerBroker(new OkxBrokerQuotationProvider());

        AssetPriceMetadata afterSecondRegistration =
                quotationService.fetch(OKX, Symbol.of(Ticker.of("BTC"), Ticker.of("EUR")));

        assertThat(afterSecondRegistration.getCurrentPrice()).isEqualTo(Price.of(66532.9, "EUR"));
    }

    // ---------- cache notowan ----------

    @Test
    void shouldReturnThePublishedQuoteInFull() {
        quotationService.onPriceChange(priceOf("BTC", "EUR", 66532.9, "EUR"));

        AssetPriceMetadata fetched = quotationService.fetch(OKX, Symbol.of(Ticker.of("BTC"), Ticker.of("EUR")));

        assertThat(fetched)
                .usingRecursiveComparison()
                .isEqualTo(AssetPriceMetadata.builder()
                        .symbol(Symbol.of(Ticker.of("BTC"), Ticker.of("EUR")))
                        .currentPrice(Price.of(66532.9, "EUR"))
                        .pctChange(0.0)
                        .dateTime(FIXED_NOW)
                        .build());
    }

    @Test
    void shouldRejectFetchOfAnUnpublishedSymbol() {
        assertThatThrownBy(() -> quotationService.fetch(OKX, Symbol.of(Ticker.of("ETH"), Ticker.of("EUR"))))
                .isInstanceOf(QuoteNotFoundException.class);
    }

    @Test
    void shouldHandleCashQuotedAgainstItself() {
        // GET /portfolio fetches a price for EVERY asset, cash included - PortfolioSummaryMapper:124
        // stubs USD/USD for exactly this reason. Without EUR/EUR the read throws on the first
        // cash position.
        quotationService.onPriceChange(priceOf("EUR", "EUR", 1.0, "EUR"));

        AssetPriceMetadata fetched = quotationService.fetch(OKX, Symbol.of(Ticker.of("EUR"), Ticker.of("EUR")));

        assertThat(fetched.getCurrentPrice()).isEqualTo(Price.of(1.0, "EUR"));
    }

    @Test
    void shouldOverwriteAQuoteWhenTheSameSymbolIsPublishedAgain() {
        quotationService.onPriceChange(priceOf("BTC", "EUR", 66532.9, "EUR"));
        quotationService.onPriceChange(priceOf("BTC", "EUR", 68000.0, "EUR"));

        assertThat(quotationService.fetch(OKX, Symbol.of(Ticker.of("BTC"), Ticker.of("EUR"))).getCurrentPrice())
                .isEqualTo(Price.of(68000.0, "EUR"));
    }

    @Test
    void shouldDropCachedQuotesWhenCachesAreCleared() {
        quotationService.onPriceChange(priceOf("BTC", "EUR", 66532.9, "EUR"));

        quotationService.clearCaches();

        assertThatThrownBy(() -> quotationService.fetch(OKX, Symbol.of(Ticker.of("BTC"), Ticker.of("EUR"))))
                .isInstanceOf(QuoteNotFoundException.class);
    }

    // ---------- zachowanie odziedziczone, dokumentujace stan faktyczny ----------

    @Test
    void shouldServeUsdQuotesFromUsdtAtParity() {
        // Inherited from BrokerQuotationProvider: a USD quote falls back to USDT and is returned
        // one-to-one, with no conversion. Pinned here so that task B4 - which introduces a real
        // USD/PLN chain - has to change this test deliberately rather than silently.
        quotationService.onPriceChange(priceOf("BTC", "USDT", 76702.7, "USDT"));

        AssetPriceMetadata usd = quotationService.fetch(OKX, Symbol.of(Ticker.of("BTC"), Ticker.of("USD")));

        assertThat(usd.getCurrentPrice()).isEqualTo(Price.of(76702.7, "USDT"));
        assertThat(usd.getSymbol()).isEqualTo(Symbol.of(Ticker.of("BTC"), Ticker.of("USD")));
    }

    @Test
    void shouldReturnNotFoundInfoForAnUnknownTickerInsteadOfThrowing() {
        // GET /portfolio calls this for every asset; throwing here would break the whole read
        AssetBasicInfo info = quotationService.fetchBasicInfoAboutAsset(OKX, Ticker.of("BTC"));

        assertThat(info).usingRecursiveComparison().isEqualTo(AssetBasicInfo.notFound(Ticker.of("BTC")));
    }

    @Test
    void shouldReturnRegisteredBasicInfoInFull() {
        AssetBasicInfo bitcoin = AssetBasicInfo.builder()
                .ticker(Ticker.of("BTC"))
                .fullName("Bitcoin")
                .segment(Segment.of("Crypto"))
                .tags(List.of("crypto"))
                .build();

        quotationService.registerAssetBasicInfo(OKX, bitcoin);

        assertThat(quotationService.fetchBasicInfoAboutAsset(OKX, Ticker.of("BTC")))
                .usingRecursiveComparison()
                .isEqualTo(bitcoin);
    }
}
