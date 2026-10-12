package com.multi.vidulum.quotation.api;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.Ticker;

import java.time.ZonedDateTime;

/**
 * Quote lookup for a test that needs prices but is not about prices.
 *
 * <p>A fake rather than a stub: it answers from the <b>real</b>
 * {@link BrokerQuotationProvider}, fed by hand instead of from Kafka. So a currency against
 * itself is one, USDT stands in for USD, and a registered {@link AssetBasicInfo} can be read
 * back — because production decides those, not this class. The day one of those rules changes,
 * every test standing on it goes red instead of quietly agreeing with a copy of the old rule.
 *
 * <p>The one thing this class does decide is what a missing price means, and that genuinely
 * differs between callers — hence two named constructors rather than a flag:
 *
 * <pre>
 *   private final InMemoryQuotationApi quotationApiFake = InMemoryQuotationApi.strict();
 *   …
 *   quotationApiFake.publish("BTC/EUR", 70_000);
 * </pre>
 *
 * <p>It replaced thirteen anonymous implementations which had already drifted apart: twelve
 * raised an assertion on a missing price, one threw {@link QuoteNotFoundException}, and one had
 * no price table at all and answered 70 000 to everything.
 *
 * <p><b>Deliberately not modelled:</b> the broker (one venue answers for every broker, because
 * no test has ever cared which), and the routes above the provider — the fixing and the chain
 * through the dollar live in {@code QuotationService}. A test <i>about</i> a chained price should
 * use the real service with real {@code FxRates}, as {@code DenominationChainComponentTest} does.
 */
public final class InMemoryQuotationApi implements QuotationApi {

    /** Nothing reads it; a published price has to be stamped with something. */
    private static final ZonedDateTime AS_OF = ZonedDateTime.parse("2022-01-01T00:00:00Z");

    private static final class Venue extends BrokerQuotationProvider {
        private Venue() {
            super(Broker.of("TEST"));
        }
    }

    private final Venue venue = new Venue();
    private final boolean missIsProductionBehaviour;

    private InMemoryQuotationApi(boolean missIsProductionBehaviour) {
        this.missIsProductionBehaviour = missIsProductionBehaviour;
    }

    /**
     * A price nobody published is a mistake in the test, and says so.
     *
     * <p>The default, because that is what it usually is — a typo in a symbol, or an assertion
     * written before the price it depends on. {@link AssertionError} also travels: callers that
     * turn a missing quote into an answer catch {@link QuoteNotFoundException}, so a stricter
     * failure is not swallowed on the way out.
     */
    public static InMemoryQuotationApi strict() {
        return new InMemoryQuotationApi(false);
    }

    /**
     * A price nobody published throws {@link QuoteNotFoundException}, exactly as the live service
     * would. For tests of what happens to a position that cannot be priced.
     */
    public static InMemoryQuotationApi asInProduction() {
        return new InMemoryQuotationApi(true);
    }

    /** @param symbol as written in a pair, e.g. {@code "BTC/EUR"} */
    public InMemoryQuotationApi publish(String symbol, double price) {
        return publish(Symbol.of(symbol), price);
    }

    /**
     * May be called after the test has started: a scenario in which the market moves is one
     * publish in the middle of it, not a second collaborator.
     */
    public InMemoryQuotationApi publish(Symbol symbol, double price) {
        venue.onPriceChange(PriceChangedEvent.builder()
                .broker(venue.getBroker())
                .symbol(symbol)
                .currentPrice(Price.of(price, symbol.getDestination().getId()))
                .pctChange(0)
                .dateTime(AS_OF)
                .build());
        return this;
    }

    @Override
    public AssetPriceMetadata fetch(Broker broker, Symbol symbol) {
        return venue.find(symbol).orElseGet(() -> onMiss(symbol));
    }

    @Override
    public AssetBasicInfo fetchBasicInfoAboutAsset(Broker broker, Ticker ticker) {
        return venue.fetchBasicInfoAboutAsset(ticker);
    }

    @Override
    public void registerBasicInfoAboutAsset(Broker broker, AssetBasicInfo assetBasicInfo) {
        venue.registerBasicInfoAboutAsset(assetBasicInfo);
    }

    private AssetPriceMetadata onMiss(Symbol symbol) {
        if (missIsProductionBehaviour) {
            throw new QuoteNotFoundException(symbol);
        }
        throw new AssertionError("the test published no price for " + symbol.getId());
    }
}
