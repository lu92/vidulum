package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.AssetPriceMetadata;
import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.OrderId;
import com.multi.vidulum.common.OrderType;
import com.multi.vidulum.common.OriginOrderId;
import com.multi.vidulum.common.OriginTradeId;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Provenance;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Side;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.events.OrderFilledEvent;
import com.multi.vidulum.common.events.TradeCapturedEvent;
import com.multi.vidulum.portfolio.app.commands.create.CreateEmptyPortfolioCommand;
import com.multi.vidulum.portfolio.app.commands.create.CreateEmptyPortfolioCommandHandler;
import com.multi.vidulum.portfolio.app.commands.deposit.DepositMoneyCommand;
import com.multi.vidulum.portfolio.app.commands.deposit.DepositMoneyCommandHandler;
import com.multi.vidulum.portfolio.app.commands.lock.LockAssetCommandHandler;
import com.multi.vidulum.portfolio.app.commands.unlock.UnlockAssetCommandHandler;
import com.multi.vidulum.portfolio.app.commands.update.ProcessTradeCommandHandler;
import com.multi.vidulum.portfolio.app.listeners.OrderFilledEventListener;
import com.multi.vidulum.portfolio.app.queries.PortfolioSummaryMapper;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.portfolio.domain.NotSufficientBalance;
import com.multi.vidulum.portfolio.domain.QuoteRestClient;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioFactory;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioRestClient;
import com.multi.vidulum.portfolio.domain.portfolio.ProfitStatus;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.trading.app.commands.orders.cancel.CancelOrderCommand;
import com.multi.vidulum.trading.app.commands.orders.cancel.CancelOrderCommandHandler;
import com.multi.vidulum.trading.app.commands.orders.create.PlaceOrderCommand;
import com.multi.vidulum.trading.app.commands.orders.create.PlaceOrderCommandHandler;
import com.multi.vidulum.trading.app.commands.orders.fill.FillOrderCommandHandler;
import com.multi.vidulum.trading.app.commands.trades.execute.MakeTradeCommand;
import com.multi.vidulum.trading.app.commands.trades.execute.MakeTradeCommandHandler;
import com.multi.vidulum.trading.app.listeners.TradeCapturedEventListener;
import com.multi.vidulum.trading.domain.DomainOrderRepository;
import com.multi.vidulum.trading.domain.DomainTradeRepository;
import com.multi.vidulum.trading.domain.Order;
import com.multi.vidulum.trading.domain.OrderFactory;
import com.multi.vidulum.trading.domain.Trade;
import com.multi.vidulum.trading.infrastructure.OrderFilledEventEmitter;
import com.multi.vidulum.trading.infrastructure.TradeCapturedEventEmitter;
import com.multi.vidulum.shared.ddd.event.DomainEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * A portfolio nobody connects to an exchange: precious metals, bought and sold by hand.
 *
 * <p>Everything else in this module assumes trading happens somewhere else and is reported back.
 * This walks the opposite case — a ledger the owner keeps themselves — end to end, and it is the
 * case that did not work: every trade used to require an order, and a purchase from a dealer is
 * not an order, it is only a fill.
 *
 * <p>Three things it is here to protect, none of which a happy-path walk would catch on its own:
 *
 * <ul>
 *   <li><b>{@code quantity = locked + free} after every step.</b> A hand-entered trade reserves
 *       nothing, so taking its units out of {@code locked} the way an order-backed one does drove
 *       that figure negative and left {@code free} untouched — a position whose parts no longer
 *       summed to its size, produced by a call that succeeded.
 *   <li><b>Troy ounces stay troy ounces.</b> {@code Quantity} carries its unit as a free string
 *       and its arithmetic ignores it, so nothing but a test notices a lock counted in
 *       {@code "Number"} against a position held in {@code "oz"}.
 *   <li><b>Valuation follows quotes, not activity.</b> Buying and selling at the market price move
 *       what is held, never what it is worth; only a new quote does that.
 * </ul>
 *
 * <p>Component level on purpose: real handlers, real aggregate, in-memory stores, and the two
 * Kafka hops replaced by direct dispatch. What is under test is the arithmetic and the routing,
 * and a broker in the middle would only make them slower to observe.
 */
class PreciousMetalsLifecycleComponentTest {

    private static final Broker PM = Broker.of("PM");
    private static final UserId OWNER = UserId.of("U10000001");
    private static final Currency USD = Currency.of("USD");
    private static final Ticker XAU = Ticker.of("XAU");
    private static final Ticker XAG = Ticker.of("XAG");
    private static final String OZ = "oz";
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2022-01-01T00:00:00Z");

    private final Clock clock = Clock.fixed(Instant.parse("2022-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final DomainPortfolioRepository portfolios = new InMemoryPortfolioRepository(clock);
    private final InMemoryOrders orders = new InMemoryOrders();
    private final InMemoryTrades trades = new InMemoryTrades();
    private final Map<String, Double> prices = new HashMap<>();

    private final CommandGateway gateway = new CommandGateway();
    private PortfolioId portfolioId;
    private int tradeCounter;
    private int contributionCounter;

    // --- wiring -----------------------------------------------------------------------------

    private final QuoteRestClient quotes = new QuoteRestClient() {
        @Override
        public AssetPriceMetadata fetch(Broker broker, Symbol symbol) {
            // A currency against itself is one, as the real provider decided in B5.
            if (symbol.getOrigin().equals(symbol.getDestination())) {
                return AssetPriceMetadata.builder()
                        .symbol(symbol).currentPrice(Price.one(symbol.getDestination().getId())).build();
            }
            Double price = prices.get(symbol.getId());
            if (price == null) {
                throw new AssertionError("no quote published for " + symbol.getId());
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
    };

    private final PortfolioSummaryMapper summaryMapper = new PortfolioSummaryMapper(quotes);
    private final LockAssetCommandHandler lockHandler = new LockAssetCommandHandler(portfolios, clock);
    private final UnlockAssetCommandHandler unlockHandler = new UnlockAssetCommandHandler(portfolios, clock);

    /** Stands in for {@code PortfolioRestClientImpl}, which lives in another module. */
    private final PortfolioRestClient portfolioClient = new ComponentPortfolioRestClient();

    @BeforeEach
    void wire() {
        gateway.registerCommandHandler(new CreateEmptyPortfolioCommandHandler(portfolios, new PortfolioFactory()));
        gateway.registerCommandHandler(new DepositMoneyCommandHandler(portfolios, clock));
        gateway.registerCommandHandler(lockHandler);
        gateway.registerCommandHandler(unlockHandler);
        gateway.registerCommandHandler(new ProcessTradeCommandHandler(portfolios));
        gateway.registerCommandHandler(new PlaceOrderCommandHandler(orders, new OrderFactory(), portfolioClient));
        gateway.registerCommandHandler(new CancelOrderCommandHandler(orders, portfolioClient));
        gateway.registerCommandHandler(new FillOrderCommandHandler(orders, new DirectOrderFilledEmitter()));
        gateway.registerCommandHandler(new MakeTradeCommandHandler(trades, orders, new DirectTradeCapturedEmitter()));

        prices.put("XAU/USD", 1800.0);
        prices.put("XAG/USD", 25.0);

        Portfolio created = gateway.send(CreateEmptyPortfolioCommand.builder()
                .portfolioId(PortfolioId.generate())
                .name("Metale szlachetne")
                .userId(OWNER)
                .broker(PM)
                .allowedDepositCurrency(USD)
                .build());
        portfolioId = created.getPortfolioId();

        deposit(20_000);
    }

    /** The two Kafka hops, dispatched in place so the whole route runs inside one assertion. */
    private final class DirectTradeCapturedEmitter extends TradeCapturedEventEmitter {
        private DirectTradeCapturedEmitter() {
            super(null);
        }

        @Override
        public void emit(TradeCapturedEvent event) {
            new TradeCapturedEventListener(gateway).on(event);
        }
    }

    private final class DirectOrderFilledEmitter extends OrderFilledEventEmitter {
        private DirectOrderFilledEmitter() {
            super(null);
        }

        @Override
        public void emit(OrderFilledEvent event) {
            new OrderFilledEventListener(gateway).on(event);
        }
    }

    // --- steps ------------------------------------------------------------------------------

    private void deposit(double amount) {
        deposit(portfolioId, Money.of(amount, "USD"));
    }

    private void deposit(PortfolioId id, Money money) {
        gateway.send(DepositMoneyCommand.builder()
                .portfolioId(id).money(money)
                .contributionId("deposit-" + (++contributionCounter))
                .build());
    }

    private PortfolioId createPortfolio(String name, Currency currency) {
        Portfolio created = gateway.send(CreateEmptyPortfolioCommand.builder()
                .portfolioId(PortfolioId.generate())
                .name(name)
                .userId(OWNER)
                .broker(PM)
                .allowedDepositCurrency(currency)
                .build());
        return created.getPortfolioId();
    }

    private void quote(String symbol, double price) {
        prices.put(symbol, price);
    }

    /** A purchase from a dealer: no order, only a fill. */
    private void tradeByHand(Side side, Ticker metal, double quantity, double price) {
        gateway.send(MakeTradeCommand.builder()
                .userId(OWNER)
                .portfolioId(portfolioId)
                .originTradeId(OriginTradeId.of("manual-" + (++tradeCounter)))
                .orderId(OrderId.notDefined())
                .symbol(Symbol.of(metal, Ticker.of("USD")))
                .side(side)
                .subName(SubName.none())
                .quantity(Quantity.of(quantity, OZ))
                .price(Price.of(price, "USD"))
                .fee(new MakeTradeCommand.Fee(Money.zero("USD"), Money.zero("USD")))
                .originDateTime(NOW)
                .build());
    }

    /** The exchange-style route, kept alongside so the two cannot drift apart unnoticed. */
    private OrderId placeOrder(Side side, Ticker metal, double quantity, double limitPrice) {
        Order order = gateway.send(PlaceOrderCommand.builder()
                .orderId(OrderId.generate())
                .originOrderId(OriginOrderId.of("origin-" + (++tradeCounter)))
                .portfolioId(portfolioId)
                .broker(PM)
                .symbol(Symbol.of(metal, Ticker.of("USD")))
                .type(OrderType.LIMIT)
                .side(side)
                .limitPrice(Price.of(limitPrice, "USD"))
                .quantity(Quantity.of(quantity, OZ))
                .occurredDateTime(NOW)
                .build());
        return order.getOrderId();
    }

    private void tradeAgainst(OrderId orderId, double quantity, double price) {
        gateway.send(MakeTradeCommand.builder()
                .userId(OWNER)
                .portfolioId(portfolioId)
                .originTradeId(OriginTradeId.of("filled-" + (++tradeCounter)))
                .orderId(orderId)
                .subName(SubName.none())
                .quantity(Quantity.of(quantity, OZ))
                .price(Price.of(price, "USD"))
                .fee(new MakeTradeCommand.Fee(Money.zero("USD"), Money.zero("USD")))
                .originDateTime(NOW)
                .build());
    }

    // --- reading ----------------------------------------------------------------------------

    private PortfolioDto.PortfolioSummaryJson summary() {
        return summaryMapper.map(portfolios.findById(portfolioId).orElseThrow(), USD);
    }

    private PortfolioDto.AssetSummaryJson position(String ticker) {
        return summary().getAssets().stream()
                .filter(asset -> asset.getTicker().equals(ticker))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no position in " + ticker));
    }

    private double valuation() {
        return summary().getCurrentValue().getAmount().doubleValue();
    }

    /**
     * The invariant every step is checked against. Asserted on units as well as amounts: a lock
     * counted in the wrong unit still adds up, and that is precisely why it goes unnoticed.
     */
    private void assertHoldingsAddUp() {
        for (PortfolioDto.AssetSummaryJson asset : summary().getAssets()) {
            assertThat(asset.getLocked().getQty() + asset.getFree().getQty())
                    .as("%s: locked + free must be the whole position", asset.getTicker())
                    .isCloseTo(asset.getQuantity().getQty(), within(1e-9));
            assertThat(asset.getLocked().getUnit())
                    .as("%s: lock is measured in the unit the position is held in", asset.getTicker())
                    .isEqualTo(asset.getQuantity().getUnit());
            assertThat(asset.getFree().getUnit())
                    .as("%s: free balance is measured in the unit the position is held in", asset.getTicker())
                    .isEqualTo(asset.getQuantity().getUnit());
        }
    }

    // --- the walk ---------------------------------------------------------------------------

    /**
     * The whole ledger, in the order someone keeping it would actually work.
     *
     * <p>One test rather than eight, because what is being protected is the <b>sequence</b>: a
     * cost that merges correctly only because the previous purchase landed where it should, a
     * lock released by the trade that claimed it. Split into independent cases, each would set up
     * its own state by hand and the seams between them would go untested — which is exactly how
     * this path came to be broken.
     */
    @Test
    void shouldKeepAHandWrittenMetalsLedgerFromFirstPurchaseToLastSale() {
        assertThat(valuation()).isEqualTo(20_000);

        // --- 1. bought from a dealer: no order, no reservation ------------------------------
        tradeByHand(Side.BUY, XAU, 5, 1800);

        assertThat(position("XAU").getQuantity()).isEqualTo(Quantity.of(5, OZ));
        assertThat(position("XAU").getCostBasis().getAvgPrice()).isEqualTo(Price.of(1800, "USD"));
        assertThat(position("XAU").getCostBasis().getProvenance()).isEqualTo(Provenance.DERIVED_FROM_FILLS);
        assertThat(position("USD").getQuantity()).isEqualTo(Quantity.of(11_000));
        assertThat(position("USD").getFree())
                .as("a purchase nobody reserved for comes straight out of the free balance")
                .isEqualTo(Quantity.of(11_000));
        assertThat(valuation())
                .as("buying at the market price moves what is held, not what it is worth")
                .isEqualTo(20_000);
        assertHoldingsAddUp();

        // --- 2. the price of gold moves ------------------------------------------------------
        quote("XAU/USD", 2000);

        assertThat(position("XAU").getQuantity())
                .as("a quote changes the valuation and nothing else")
                .isEqualTo(Quantity.of(5, OZ));
        assertThat(valuation()).isEqualTo(21_000);
        assertThat(position("XAU").getUnrealisedProfit()).isEqualTo(Money.of(1000, "USD"));
        assertThat(summary().getProfitStatus()).isEqualTo(ProfitStatus.COMPUTED);
        assertThat(summary().getUnrealisedProfit()).isEqualTo(Money.of(1000, "USD"));

        // --- 3. the other route: an order reserves the cash, the fill consumes it ------------
        OrderId silverOrder = placeOrder(Side.BUY, XAG, 100, 25);

        assertThat(position("USD").getLocked())
                .as("placing the order sets the money aside")
                .isEqualTo(Quantity.of(2500));
        assertThat(position("USD").getFree()).isEqualTo(Quantity.of(8500));
        assertHoldingsAddUp();

        tradeAgainst(silverOrder, 100, 25);

        assertThat(position("USD").getQuantity()).isEqualTo(Quantity.of(8500));
        assertThat(position("USD").getLocked())
                .as("the fill consumes the reservation rather than the free balance")
                .isEqualTo(Quantity.of(0));
        assertThat(position("USD").getFree()).isEqualTo(Quantity.of(8500));
        assertThat(position("XAG").getQuantity()).isEqualTo(Quantity.of(100, OZ));
        assertThat(position("XAG").getLocked())
                .as("a new metal position counts its lock in ounces, not in bare numbers")
                .isEqualTo(Quantity.of(0, OZ));
        assertThat(valuation()).isEqualTo(21_000);
        assertHoldingsAddUp();

        // --- 4. more gold, at a different price: the cost has to merge -----------------------
        tradeByHand(Side.BUY, XAU, 3, 2000);

        assertThat(position("XAU").getQuantity()).isEqualTo(Quantity.of(8, OZ));
        assertThat(position("XAU").getCostBasis().getAvgPrice())
                .as("(5 x 1800 + 3 x 2000) / 8")
                .isEqualTo(Price.of(1875, "USD"));
        assertThat(position("USD").getQuantity()).isEqualTo(Quantity.of(2500));
        assertThat(valuation()).isEqualTo(21_000);
        assertHoldingsAddUp();

        // --- 5. silver moves too --------------------------------------------------------------
        quote("XAG/USD", 30);

        assertThat(valuation()).isEqualTo(21_500);
        assertThat(position("XAG").getUnrealisedProfit()).isEqualTo(Money.of(500, "USD"));
        assertThat(summary().getUnrealisedProfit())
                .as("1000 on gold, 500 on silver, nothing on cash held at par")
                .isEqualTo(Money.of(1500, "USD"));
        assertThat(summary().getPctUnrealisedProfit()).isCloseTo(0.075, within(1e-9));
        assertThat(summary().getProfitCoverage())
                .as("a ledger kept by hand knows what everything cost")
                .isEqualTo(1.0);

        // --- 6. part of the gold goes ---------------------------------------------------------
        tradeByHand(Side.SELL, XAU, 2, 2000);

        assertThat(position("XAU").getQuantity()).isEqualTo(Quantity.of(6, OZ));
        assertThat(position("XAU").getCostBasis().getQuantity())
                .as("selling reduces the quantity the cost covers")
                .isEqualTo(Quantity.of(6, OZ));
        assertThat(position("XAU").getCostBasis().getAvgPrice())
                .as("and leaves the price it was bought at alone")
                .isEqualTo(Price.of(1875, "USD"));
        assertThat(position("USD").getQuantity()).isEqualTo(Quantity.of(6500));
        assertThat(valuation()).isEqualTo(21_500);
        assertHoldingsAddUp();

        // --- 7. the silver goes entirely --------------------------------------------------------
        tradeByHand(Side.SELL, XAG, 100, 30);

        assertThat(summary().getAssets())
                .as("a position sold out is gone, not held at zero")
                .noneMatch(asset -> asset.getTicker().equals("XAG"));
        assertThat(position("USD").getQuantity()).isEqualTo(Quantity.of(9500));
        assertThat(valuation()).isEqualTo(21_500);
        assertHoldingsAddUp();

        // The 500 made on silver is realised now, and no longer anywhere in this summary: the
        // figure speaks for what is still held. Reporting it is task F6, and the number below
        // is here to make its absence deliberate rather than accidental.
        assertThat(summary().getUnrealisedProfit())
                .as("gold only: 6 oz worth 12 000 against 11 250 of cost")
                .isEqualTo(Money.of(750, "USD"));

        // --- 8. and the price turns ------------------------------------------------------------
        quote("XAU/USD", 1500);

        assertThat(valuation()).isEqualTo(18_500);
        assertThat(position("XAU").getUnrealisedProfit())
                .as("6 x 1500 against 11 250 of cost")
                .isEqualTo(Money.of(-2250, "USD"));
        assertThat(summary().getUnrealisedProfit()).isEqualTo(Money.of(-2250, "USD"));
        assertThat(summary().getPctUnrealisedProfit()).isNegative();
        assertThat(summary().getProfitStatus()).isEqualTo(ProfitStatus.COMPUTED);
        assertHoldingsAddUp();
    }

    /** Selling metal the ledger does not hold is refused, as it always was. */
    @Test
    void shouldRefuseToSellMoreThanIsHeld() {
        tradeByHand(Side.BUY, XAU, 5, 1800);

        assertThatThrownBy(() -> tradeByHand(Side.SELL, XAU, 10, 1800))
                .isInstanceOf(NotSufficientBalance.class);

        assertThat(position("XAU").getQuantity()).isEqualTo(Quantity.of(5, OZ));
        assertHoldingsAddUp();
    }

    /**
     * And selling metal an open order is holding is refused too — which it was not.
     *
     * <p>The old check compared against the whole position, so a hand-entered sale could spend
     * units already reserved for an order, leaving that order unfillable and the reservation
     * pointing at metal that had been sold.
     */
    @Test
    void shouldRefuseToSellMetalAnOpenOrderHasReserved() {
        tradeByHand(Side.BUY, XAU, 5, 1800);
        placeOrder(Side.SELL, XAU, 4, 2000);

        assertThat(position("XAU").getLocked()).isEqualTo(Quantity.of(4, OZ));
        assertThat(position("XAU").getFree()).isEqualTo(Quantity.of(1, OZ));

        assertThatThrownBy(() -> tradeByHand(Side.SELL, XAU, 3, 2000))
                .as("three ounces are held, but only one of them is free")
                .isInstanceOf(NotSufficientBalance.class);

        assertThat(position("XAU").getQuantity()).isEqualTo(Quantity.of(5, OZ));
        assertHoldingsAddUp();
    }

    /** A trade with no order has to say what it was, because no order will say it for them. */
    @Test
    void shouldRefuseAHandEnteredTradeThatDoesNotSayWhatWasTraded() {
        assertThatThrownBy(() -> gateway.send(MakeTradeCommand.builder()
                .userId(OWNER)
                .portfolioId(portfolioId)
                .originTradeId(OriginTradeId.of("nameless"))
                .orderId(OrderId.notDefined())
                .subName(SubName.none())
                .quantity(Quantity.of(1, OZ))
                .price(Price.of(1800, "USD"))
                .fee(new MakeTradeCommand.Fee(Money.zero("USD"), Money.zero("USD")))
                .originDateTime(NOW)
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbol and side");
    }

    /**
     * Money paid in while an order is standing does not hand back what that order reserved.
     *
     * <p>The deposit used to set {@code free} to the whole balance rather than add to it, so a
     * position could report 4 000 locked and 11 000 free out of 11 000 held. The same units were
     * then spendable twice, and the order they backed could no longer be filled — reachable from
     * the plainest sequence there is: place an order, pay some money in.
     */
    @Test
    void shouldNotReleaseReservedCashWhenMoreMoneyIsPaidIn() {
        placeOrder(Side.BUY, XAU, 2, 2000);

        assertThat(position("USD").getLocked()).isEqualTo(Quantity.of(4000));
        assertThat(position("USD").getFree()).isEqualTo(Quantity.of(16_000));

        deposit(5_000);

        assertThat(position("USD").getQuantity()).isEqualTo(Quantity.of(25_000));
        assertThat(position("USD").getLocked())
                .as("the order still holds what it reserved")
                .isEqualTo(Quantity.of(4000));
        assertThat(position("USD").getFree())
                .as("the deposit is added to what was free, not substituted for it")
                .isEqualTo(Quantity.of(21_000));
        assertHoldingsAddUp();
    }

    /**
     * Cancelling a sale gives the metal back in ounces.
     *
     * <p>Both sides of a cancellation used to be released as {@code Order.getTotal()}, which for
     * a sale answered {@code Money.one("USD") x quantity} — the quantity smuggled through a money
     * in dollars.
     *
     * <p>This test would have passed then too, and saying so matters: multiplying by one changes
     * nothing, so the number came out right and only the currency was a fiction. It records the
     * behaviour rather than guarding the fix. What guards it is {@code Order.getTotal()} refusing
     * a sale outright — see {@code OrderTest.shouldRefuseToStateWhatASaleCosts}.
     */
    @Test
    void shouldGiveBackTheMetalInOuncesWhenASaleIsCancelled() {
        tradeByHand(Side.BUY, XAU, 5, 1800);
        OrderId sale = placeOrder(Side.SELL, XAU, 2, 2000);

        assertThat(position("XAU").getLocked()).isEqualTo(Quantity.of(2, OZ));
        assertThat(position("XAU").getFree()).isEqualTo(Quantity.of(3, OZ));

        gateway.send(CancelOrderCommand.builder().orderId(sale).build());

        assertThat(position("XAU").getLocked()).isEqualTo(Quantity.of(0, OZ));
        assertThat(position("XAU").getFree()).isEqualTo(Quantity.of(5, OZ));
        assertThat(position("XAU").getActiveLocks())
                .as("and the record of the reservation goes with it")
                .isEmpty();
        assertHoldingsAddUp();
    }

    /**
     * A portfolio that settles in something other than dollars can still place an order.
     *
     * <p>The balance check ahead of an order reads the portfolio through
     * {@code PortfolioRestClient}, and every implementation valued it in USD whatever it actually
     * settled in. A zloty portfolio was compared against dollar amounts and needed USD quotes for
     * assets nobody had priced that way — so it could not trade at all.
     */
    @Test
    void shouldLetAPortfolioThatSettlesInZlotyPlaceAnOrder() {
        Currency pln = Currency.of("PLN");
        PortfolioId zlotyPortfolio = createPortfolio("Metale w złotych", pln);
        deposit(zlotyPortfolio, Money.of(40_000, "PLN"));
        quote("XAU/PLN", 7000);

        Order order = gateway.send(PlaceOrderCommand.builder()
                .orderId(OrderId.generate())
                .originOrderId(OriginOrderId.of("pln-1"))
                .portfolioId(zlotyPortfolio)
                .broker(PM)
                .symbol(Symbol.of(XAU, Ticker.of("PLN")))
                .type(OrderType.LIMIT)
                .side(Side.BUY)
                .limitPrice(Price.of(7000, "PLN"))
                .quantity(Quantity.of(5, OZ))
                .occurredDateTime(NOW)
                .build());

        assertThat(order.getOrderId()).isNotNull();

        PortfolioDto.PortfolioSummaryJson summary =
                summaryMapper.map(portfolios.findById(zlotyPortfolio).orElseThrow(), pln);
        PortfolioDto.AssetSummaryJson cash = summary.getAssets().stream()
                .filter(asset -> asset.getTicker().equals("PLN")).findFirst().orElseThrow();

        assertThat(cash.getLocked())
                .as("35 000 zloty set aside, counted in zloty")
                .isEqualTo(Quantity.of(35_000));
        assertThat(cash.getFree()).isEqualTo(Quantity.of(5_000));
        assertThat(summary.getCurrentValue()).isEqualTo(Money.of(40_000, "PLN"));
    }

    // --- in-memory stores --------------------------------------------------------------------

    private static final class InMemoryOrders implements DomainOrderRepository {
        private final Map<String, Order> store = new ConcurrentHashMap<>();

        @Override
        public Optional<Order> findById(OrderId orderId) {
            return Optional.ofNullable(store.get(orderId.getId()));
        }

        @Override
        public Order save(Order order) {
            store.put(order.getOrderId().getId(), order);
            return order;
        }

        @Override
        public List<Order> findOpenedOrdersForPortfolio(PortfolioId portfolioId) {
            return store.values().stream()
                    .filter(order -> order.getPortfolioId().equals(portfolioId))
                    .filter(Order::isOpen)
                    .toList();
        }

        @Override
        public Optional<Order> findByOriginOrderId(OriginOrderId originOrderId) {
            return store.values().stream()
                    .filter(order -> order.getOriginOrderId().equals(originOrderId))
                    .findFirst();
        }

        @Override
        public List<DomainEvent> findDomainEvents(OrderId orderId) {
            return List.of();
        }
    }

    private final class InMemoryTrades implements DomainTradeRepository {
        private final List<Trade> store = new ArrayList<>();

        @Override
        public Optional<Trade> findById(com.multi.vidulum.common.TradeId tradeId) {
            return store.stream().filter(trade -> tradeId.equals(trade.getTradeId())).findFirst();
        }

        @Override
        public Trade save(Trade trade) {
            // The Mongo repository assigns the id on write; doing the same here keeps the handler
            // that emits the captured event working against a trade that has one.
            if (trade.getTradeId() == null) {
                trade.setTradeId(com.multi.vidulum.common.TradeId.of("trade-" + (store.size() + 1)));
            }
            store.add(trade);
            return trade;
        }

        @Override
        public List<Trade> findByUserIdAndPortfolioId(UserId userId, PortfolioId portfolioId) {
            return store.stream()
                    .filter(trade -> trade.getUserId().equals(userId)
                            && trade.getPortfolioId().equals(portfolioId))
                    .toList();
        }

        @Override
        public List<Trade> findByUserIdAndPortfolioIdInDateRange(
                UserId userId, ZonedDateTime from, ZonedDateTime to) {
            return List.copyOf(store);
        }
    }

    private final class ComponentPortfolioRestClient implements PortfolioRestClient {
        @Override
        public PortfolioId createPortfolio(String name, UserId userId, Broker broker, Currency currency) {
            throw new UnsupportedOperationException("the walk creates its portfolio through the gateway");
        }

        @Override
        public void lockAsset(PortfolioId id, Ticker ticker, OrderId orderId, Quantity quantity) {
            lockHandler.handle(com.multi.vidulum.portfolio.app.commands.lock.LockAssetCommand.builder()
                    .portfolioId(id).ticker(ticker).orderId(orderId).quantity(quantity).build());
        }

        @Override
        public void unlockAsset(PortfolioId id, Ticker ticker, OrderId orderId, Quantity quantity) {
            unlockHandler.handle(com.multi.vidulum.portfolio.app.commands.unlock.UnlockAssetCommand.builder()
                    .portfolioId(id).ticker(ticker).orderId(orderId).quantity(quantity).build());
        }

        @Override
        public PortfolioDto.PortfolioSummaryJson getPortfolio(PortfolioId id) {
            Portfolio portfolio = portfolios.findById(id).orElseThrow();
            return summaryMapper.map(portfolio, portfolio.getAllowedDepositCurrency());
        }

        @Override
        public PortfolioDto.AggregatedPortfolioSummaryJson getAggregatedPortfolio(UserId userId) {
            throw new UnsupportedOperationException("not part of this walk - see C6");
        }
    }
}
