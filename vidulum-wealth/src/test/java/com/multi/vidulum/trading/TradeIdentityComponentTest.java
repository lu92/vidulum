package com.multi.vidulum.trading;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.OriginTradeId;
import com.multi.vidulum.common.OrderId;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Side;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.TradeId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.events.TradeCapturedEvent;
import com.multi.vidulum.trading.app.commands.trades.execute.MakeTradeCommand;
import com.multi.vidulum.trading.app.commands.trades.execute.MakeTradeCommandHandler;
import com.multi.vidulum.trading.domain.DomainTradeRepository;
import com.multi.vidulum.trading.domain.Trade;
import com.multi.vidulum.trading.domain.DomainOrderRepository;
import com.multi.vidulum.trading.domain.Order;
import com.multi.vidulum.trading.infrastructure.TradeCapturedEventEmitter;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recording the same trade twice (task F1).
 *
 * <p>A trade can reach us more than once and none of the ways are exotic: Kafka redelivers after a
 * failure — I watched one retry nine times — an exchange repeats a fill, a CSV export is re-imported
 * over a range that overlaps the last one, a form is submitted twice. Until now every arrival wrote
 * a new row, which doubled the holding, doubled the cash it cost, and since F6 doubles the settled
 * result too — the one figure somebody copies into a tax return.
 *
 * <p>The rule is that a duplicate is a <b>success</b>: the caller asked for something already true.
 * Failing it would only send the same message round again.
 */
class TradeIdentityComponentTest {

    private static final UserId ALICE = UserId.of("U10000001");
    private static final PortfolioId PORTFOLIO = PortfolioId.of("portfolio-1");
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2022-01-01T00:00:00Z");

    private final InMemoryTrades trades = new InMemoryTrades();
    private final List<TradeCapturedEvent> emitted = new ArrayList<>();
    /** Collects what would have gone to Kafka, so a second emission is visible rather than sent. */
    private final TradeCapturedEventEmitter emitter = new TradeCapturedEventEmitter(null) {
        @Override
        public void emit(TradeCapturedEvent event) {
            emitted.add(event);
        }
    };

    private final MakeTradeCommandHandler handler =
            new MakeTradeCommandHandler(trades, new NoOrders(), emitter);

    private Trade record(String originTradeId, double quantity) {
        return handler.handle(MakeTradeCommand.builder()
                .userId(ALICE)
                .portfolioId(PORTFOLIO)
                .originTradeId(OriginTradeId.of(originTradeId))
                .orderId(OrderId.notDefined())
                .symbol(Symbol.of("BTC/USD"))
                .subName(SubName.traded())
                .side(Side.BUY)
                .quantity(Quantity.of(quantity))
                .price(Price.of(40_000, "USD"))
                .fee(new MakeTradeCommand.Fee(Money.zero("USD"), Money.zero("USD")))
                .originDateTime(NOW)
                .build());
    }

    @Test
    void shouldRecordATradeTheFirstTimeItArrives() {
        Trade stored = record("okx-1", 1);

        assertThat(trades.store).hasSize(1);
        assertThat(stored.getOriginTradeId()).isEqualTo(OriginTradeId.of("okx-1"));
        assertThat(emitted).hasSize(1);
    }

    /**
     * The redelivery case, which is the one that actually happens. Nothing is written and nothing
     * is emitted — an event for a trade already applied would apply it to the portfolio twice.
     */
    @Test
    void shouldNotRecordTheSameTradeTwice() {
        Trade first = record("okx-1", 1);
        Trade again = record("okx-1", 1);

        assertThat(trades.store).hasSize(1);
        assertThat(again.getTradeId())
                .as("the caller gets the trade that exists, not a second one")
                .isEqualTo(first.getTradeId());
        assertThat(emitted)
                .as("re-emitting would apply the same purchase to the portfolio again")
                .hasSize(1);
    }

    /**
     * The re-imported CSV: a later export covering an overlapping range brings back trades we
     * already have, and only the new ones are recorded.
     */
    @Test
    void shouldKeepOnlyWhatIsNewWhenAnOverlappingRangeIsImportedAgain() {
        record("okx-1", 1);
        record("okx-2", 2);

        record("okx-2", 2);
        record("okx-3", 3);

        assertThat(trades.store).extracting(trade -> trade.getOriginTradeId().getId())
                .containsExactly("okx-1", "okx-2", "okx-3");
    }

    /**
     * Identity is scoped to the portfolio: two exchanges can mint the same string, and a global
     * rule would refuse the second owner's perfectly real trade.
     */
    @Test
    void shouldAllowTheSameOriginIdInAnotherPortfolio() {
        record("fill-7", 1);

        handler.handle(MakeTradeCommand.builder()
                .userId(ALICE)
                .portfolioId(PortfolioId.of("portfolio-2"))
                .originTradeId(OriginTradeId.of("fill-7"))
                .orderId(OrderId.notDefined())
                .symbol(Symbol.of("BTC/USD"))
                .subName(SubName.traded())
                .side(Side.BUY)
                .quantity(Quantity.of(1))
                .price(Price.of(40_000, "USD"))
                .fee(new MakeTradeCommand.Fee(Money.zero("USD"), Money.zero("USD")))
                .originDateTime(NOW)
                .build());

        assertThat(trades.store).hasSize(2);
    }

    /**
     * Two genuine trades of the same size at the same price are two trades. Deduplicating on
     * content would refuse the second — and buying the same amount twice in a day is ordinary.
     */
    @Test
    void shouldRecordTwoIdenticalTradesThatCarryDifferentIdentities() {
        record("okx-1", 0.5);
        record("okx-2", 0.5);

        assertThat(trades.store).hasSize(2);
    }

    /** A minted id is a real id: unique by construction, so nothing collides with it. */
    @Test
    void shouldMintAnIdentityThatCollidesWithNothing() {
        assertThat(OriginTradeId.generate().getId())
                .isNotBlank()
                .isNotEqualTo(OriginTradeId.generate().getId());
    }

    // --- stubs ------------------------------------------------------------------------------------

    private static final class InMemoryTrades implements DomainTradeRepository {
        private final List<Trade> store = new ArrayList<>();

        @Override
        public Optional<Trade> findById(TradeId tradeId) {
            return store.stream().filter(trade -> tradeId.equals(trade.getTradeId())).findFirst();
        }

        @Override
        public Optional<Trade> findByOrigin(PortfolioId portfolioId, OriginTradeId originTradeId) {
            return store.stream()
                    .filter(trade -> trade.getPortfolioId().equals(portfolioId)
                            && trade.getOriginTradeId().equals(originTradeId))
                    .findFirst();
        }

        @Override
        public Trade save(Trade trade) {
            if (trade.getTradeId() == null) {
                trade.setTradeId(TradeId.of("trade-" + (store.size() + 1)));
            }
            store.add(trade);
            return trade;
        }

        @Override
        public List<Trade> findByUserIdAndPortfolioId(UserId userId, PortfolioId portfolioId) {
            return List.copyOf(store);
        }

        @Override
        public List<Trade> findByUserIdAndPortfolioIdInDateRange(
                UserId userId, ZonedDateTime from, ZonedDateTime to) {
            return List.copyOf(store);
        }
    }

    /** Every trade here is hand-entered, so no order is ever looked up. */
    private static final class NoOrders implements DomainOrderRepository {
        @Override
        public Optional<Order> findById(OrderId orderId) {
            return Optional.empty();
        }

        @Override
        public Order save(Order order) {
            return order;
        }

        @Override
        public List<Order> findOpenedOrdersForPortfolio(PortfolioId portfolioId) {
            return List.of();
        }

        @Override
        public Optional<Order> findByOriginOrderId(com.multi.vidulum.common.OriginOrderId originOrderId) {
            return Optional.empty();
        }

        @Override
        public List<com.multi.vidulum.shared.ddd.event.DomainEvent> findDomainEvents(OrderId orderId) {
            return List.of();
        }
    }
}
