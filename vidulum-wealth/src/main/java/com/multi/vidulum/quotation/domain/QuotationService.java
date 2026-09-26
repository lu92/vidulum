package com.multi.vidulum.quotation.domain;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.PriceOrigin;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.quotation.domain.fx.FxRate;
import com.multi.vidulum.quotation.domain.fx.FxRates;
import org.springframework.kafka.annotation.KafkaListener;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class QuotationService {

    /**
     * The one currency every route goes through. Exchanges quote against the dollar and the
     * central bank quotes the dollar, so a declared pivot covers the real need with two legs.
     *
     * <p>A general search over all known pairs would cover more and answer worse: where several
     * routes exist they disagree, and picking "the shortest" is arbitration disguised as
     * arithmetic. A named pivot can be read, questioned and changed; a route chosen at runtime
     * can only be reconstructed from logs.
     */
    private static final Ticker PIVOT = Ticker.of("USD");

    private final Map<Broker, BrokerQuotationProvider> registeredBrokers = new ConcurrentHashMap<>();

    /** What the backend knows about currencies, as opposed to about markets. */
    private final FxRates fxRates;

    /**
     * Always given, even when empty. There is no mode in which currency knowledge is absent — an
     * empty keeper simply knows no rates yet, and says so the same way it will say it on the day a
     * source goes down. A null-tolerant service would have two spellings of the same state.
     */
    public QuotationService(FxRates fxRates) {
        this.fxRates = Objects.requireNonNull(fxRates, "Quote lookup needs somewhere to read currency rates from");
    }

    public void registerBroker(BrokerQuotationProvider brokerQuotationProvider) {
        registeredBrokers.putIfAbsent(brokerQuotationProvider.getBroker(), brokerQuotationProvider);
    }

    @KafkaListener(
            groupId = "group_id1",
            topics = "quotes",
            containerFactory = "priceChangingContainerFactory")
    public void onPriceChange(PriceChangedEvent event) {
        findBrokerOrRaiseException(event.getBroker(), brokerProvider -> {
            brokerProvider.onPriceChange(event);
            return null;
        });
    }

    /** Which brokers this instance can serve at all. */
    public Set<Broker> registeredBrokers() {
        return Set.copyOf(registeredBrokers.keySet());
    }

    public boolean isRegistered(Broker broker) {
        return registeredBrokers.containsKey(broker);
    }

    /** What the broker can price right now, identity pairs aside — those never need publishing. */
    public Set<Symbol> quotedSymbols(Broker broker) {
        return findBrokerOrRaiseException(broker, BrokerQuotationProvider::quotedSymbols);
    }

    /**
     * The price of one asset in one currency, by whatever route can answer.
     *
     * <p>The broker is asked first and its answer always wins: a pair the market actually trades
     * needs no reconstruction. Only when it has nothing does the chain run, and only then does
     * anything else get consulted.
     */
    public AssetPriceMetadata fetch(Broker broker, Symbol symbol) {
        return findBrokerOrRaiseException(broker, provider -> provider.find(symbol)
                .or(() -> fromFixing(symbol))
                .or(() -> chainThroughPivot(provider, symbol))
                .orElseThrow(() -> new QuoteNotFoundException(symbol)));
    }

    /**
     * One currency in another, straight from the fixing that publishes exactly that pair.
     *
     * <p>Tried before the chain, because a euro valued in złoty has no business travelling through
     * the dollar: the detour needs a market quote that may not exist, and multiplies two roundings
     * to reach a number the central bank already published. Found only when the live stack held
     * EUR/PLN and never used it — every route went through the pivot.
     */
    private Optional<AssetPriceMetadata> fromFixing(Symbol symbol) {
        if (symbol.getOrigin().equals(symbol.getDestination())) {
            return Optional.empty();
        }
        return fxRates.rateOf(Currency.of(symbol.getOrigin().getId()), Currency.of(symbol.getDestination().getId()))
                .map(rate -> AssetPriceMetadata.builder()
                        .symbol(symbol)
                        .currentPrice(rate.rate())
                        // A fixing states a level, not a move: it carries no percentage, and
                        // inventing one would be read as the market having done something.
                        .pctChange(0)
                        .dateTime(rate.asOf())
                        .origin(PriceOrigin.FIXING)
                        .build());
    }

    /**
     * {@code BTC/PLN} as {@code BTC/USD} from the market times {@code USD/PLN} from the fixing.
     *
     * <p>Two things the result carries are worth stating, because both could have been faked into
     * looking better. Its moment is the <b>older</b> of the two legs: a price is exactly as current
     * as the stalest thing it was built from, and a daily fixing is usually that. Its percentage
     * change is the <b>market leg's</b>, which is the movement of the asset and not of the pair —
     * folding in a fixing that moves once a day would report a currency revaluation as a market
     * move, and the reader cannot tell them apart from the number alone.
     */
    private Optional<AssetPriceMetadata> chainThroughPivot(BrokerQuotationProvider provider, Symbol symbol) {
        if (symbol.getDestination().equals(PIVOT)) {
            // Nothing to chain: the leg that is missing is the market one, and no currency rate
            // brings back a price the market never published.
            return Optional.empty();
        }
        Optional<FxRate> pivotToDestination = fxRates.rateOf(
                Currency.of(PIVOT.getId()), Currency.of(symbol.getDestination().getId()));
        if (pivotToDestination.isEmpty()) {
            return Optional.empty();
        }
        Optional<AssetPriceMetadata> assetInPivot = provider.find(Symbol.of(symbol.getOrigin(), PIVOT));
        if (assetInPivot.isEmpty()) {
            return Optional.empty();
        }

        FxRate rate = pivotToDestination.get();
        AssetPriceMetadata market = assetInPivot.get();
        return Optional.of(AssetPriceMetadata.builder()
                .symbol(symbol)
                .currentPrice(rate.convert(market.getCurrentPrice()))
                .pctChange(market.getPctChange())
                .dateTime(older(market.getDateTime(), rate.asOf()))
                .origin(PriceOrigin.CHAINED)
                .build());
    }

    private static ZonedDateTime older(ZonedDateTime one, ZonedDateTime other) {
        if (one == null) {
            return other;
        }
        if (other == null) {
            return one;
        }
        return one.isBefore(other) ? one : other;
    }

    public AssetBasicInfo fetchBasicInfoAboutAsset(Broker broker, Ticker ticker) {
        return findBrokerOrRaiseException(broker, brokerProvider -> brokerProvider.fetchBasicInfoAboutAsset(ticker));
    }

    public void registerAssetBasicInfo(Broker broker, AssetBasicInfo assetBasicInfo) {
        findBrokerOrRaiseException(broker, brokerQuotationProvider -> {
            brokerQuotationProvider.registerBasicInfoAboutAsset(assetBasicInfo);
            return null;
        });
    }

    private <R> R findBrokerOrRaiseException(Broker broker, Function<BrokerQuotationProvider, R> callback) {
        if (registeredBrokers.containsKey(broker)) {
            BrokerQuotationProvider brokerQuotationProvider = registeredBrokers.get(broker);
            return callback.apply(brokerQuotationProvider);
        } else {
            throw new BrokerNotFoundException(broker);
        }
    }

    public void clearCaches() {
        registeredBrokers.values().forEach(brokerQuotationProvider -> {
            brokerQuotationProvider.clearCaches();
        });
    }
}
