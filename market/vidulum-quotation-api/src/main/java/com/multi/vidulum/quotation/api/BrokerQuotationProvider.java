package com.multi.vidulum.quotation.api;

import com.multi.vidulum.shared.module.ModuleSpi;
import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.PriceOrigin;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.Ticker;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The per-exchange half of quote lookup: a cache of what one broker has published, plus the two
 * substitutions that are true for every exchange rather than for one.
 *
 * <p>An exchange's own module extends this and declares nothing but its broker identity — see
 * {@code OkxBrokerQuotationProvider}. Everything a provider could get wrong is {@code final} here
 * on purpose: the USD/USDT stand-in, the identity pair, and feeding the cache are rules of the
 * whole system, not of one venue. They are {@code public} only because the service that drives
 * them lives in another artifact, and {@code final} is what carries the protection that used to
 * come from them being package-private — which guarded against providers in other packages while
 * leaving one in this package free to override.
 */
@Slf4j
@AllArgsConstructor
public abstract class BrokerQuotationProvider implements ModuleSpi {
    @Getter
    protected Broker broker;
    private final ConcurrentHashMap<Symbol, AssetPriceMetadata> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Ticker, AssetBasicInfo> basicInfo = new ConcurrentHashMap<>();


    public final void onPriceChange(PriceChangedEvent event) {
        AssetPriceMetadata priceMetadata = AssetPriceMetadata.builder()
                .symbol(event.getSymbol())
                .currentPrice(event.getCurrentPrice())
                .pctChange(event.getPctChange())
                .dateTime(event.getDateTime())
                .build();
        cache.put(event.getSymbol(), priceMetadata);
        log.info("[{}] Price of [{}] has been updated to [{}]", getBroker(), event.getSymbol().getId(), event.getCurrentPrice());
    }

    /**
     * The symbols this provider can price right now. Used to answer "are quotes ready" before a
     * portfolio is created — see the exchange status endpoint.
     */
    public Set<Symbol> quotedSymbols() {
        return Set.copyOf(cache.keySet());
    }

    public final AssetPriceMetadata fetch(Symbol symbol) {
        return find(symbol).orElseThrow(() -> new QuoteNotFoundException(symbol));
    }

    /**
     * What this broker can say about the pair, without deciding that silence is an error.
     *
     * <p>Separated from {@link #fetch(Symbol)} so that a caller able to do something with a miss —
     * the denomination chain in {@code QuotationService} — is not made to catch an exception to
     * find out. Exhausting every route and only then refusing is that caller's job, not this one's.
     */
    public final Optional<AssetPriceMetadata> find(Symbol symbol) {
        // A currency against itself is one, by definition. Checked before the cache on purpose:
        // it is arithmetic, not market data, so nothing published should be able to contradict it.
        //
        // Without this, cash breaks portfolio valuation. GET /portfolio prices *every* asset,
        // including cash, and cash in a portfolio valued in the same currency is the symbol
        // EUR/EUR - which nobody would think to publish, and whose absence throws.
        if (symbol.getOrigin().equals(symbol.getDestination())) {
            return Optional.of(AssetPriceMetadata.builder()
                    .symbol(symbol)
                    .currentPrice(Price.one(symbol.getDestination().getId()))
                    .pctChange(0)
                    .dateTime(ZonedDateTime.now())
                    .origin(PriceOrigin.DIRECT)
                    .build());
        }
        if (cache.containsKey(symbol)) {
            return Optional.of(cache.get(symbol));
        }
        if (symbol.getDestination().equals(Ticker.of("USD"))) {
            // The dollar stablecoin standing in for the dollar. Worth a penny in a normal week and
            // worth watching in an abnormal one - which is why the answer says it was substituted
            // rather than published.
            AssetPriceMetadata priceMetadata = cache.get(Symbol.of(symbol.getOrigin(), Ticker.of("USDT")));
            if (priceMetadata == null) {
                return Optional.empty();
            }
            return Optional.of(AssetPriceMetadata.builder()
                    .symbol(Symbol.of(symbol.getOrigin(), Ticker.of("USD")))
                    // Restated in dollars, which is what the substitution claims. It used to be
                    // handed back still labelled USDT, so the answer's own price contradicted the
                    // symbol above it - harmless while nothing checked, and wrong the moment
                    // anything did.
                    .currentPrice(Price.of(priceMetadata.getCurrentPrice().getAmount(), "USD"))
                    .pctChange(priceMetadata.getPctChange())
                    .dateTime(priceMetadata.getDateTime())
                    .origin(PriceOrigin.SUBSTITUTED)
                    .build());
        }
        return Optional.empty();
    }

    public AssetBasicInfo fetchBasicInfoAboutAsset(Ticker ticker) {
        if (basicInfo.containsKey(ticker)) {
            return basicInfo.get(ticker);
        } else {
            return AssetBasicInfo.notFound(ticker);
        }
    }

    public void registerBasicInfoAboutAsset(AssetBasicInfo assetBasicInfo) {
        basicInfo.put(assetBasicInfo.getTicker(), assetBasicInfo);
        log.info("Basic Info for [{}] updated: [{}]", assetBasicInfo.getTicker(), assetBasicInfo);
    }

    public void clearCaches() {
        cache.clear();
        basicInfo.clear();
    }
}
