package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.CostBasis;
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
import com.multi.vidulum.portfolio.domain.portfolio.Asset;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.trades.ExecutedTrade;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same trade delivered twice (task F10).
 *
 * <p>F1 stopped a trade being <b>stored</b> twice. This is the other half: a trade already stored
 * and already applied, arriving again because Kafka redelivered it — which it does, and I have
 * watched it retry nine times. Without a memory of what has been counted, the portfolio adds the
 * same purchase again, and since F6 counts its settled result again too.
 *
 * <p>The memory lives in the portfolio, so the check and the change are one write. Split across
 * two documents with no transaction, either order loses: mark first and a failed apply drops the
 * trade for good; apply first and a crash before marking counts it twice.
 */
class AppliedTwiceComponentTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2022-01-01T00:00:00Z");

    private static Portfolio portfolioWithCash(double cash) {
        return PortfolioFixture.portfolio()
                .at(com.multi.vidulum.common.Broker.of("PM")).denominatedIn("USD")
                .withCash("USD", Quantity.of(cash))
                .build();
    }

    private static ExecutedTrade buy(String tradeId, double quantity, double price) {
        return ExecutedTrade.builder()
                .portfolioId(com.multi.vidulum.common.PortfolioId.of("portfolio-1"))
                .tradeId(TradeId.of(tradeId))
                .orderId(OrderId.notDefined())
                .symbol(Symbol.of(Ticker.of("XAU"), Ticker.of("USD")))
                .subName(SubName.traded())
                .side(Side.BUY)
                .quantity(Quantity.of(quantity))
                .price(Price.of(price, "USD"))
                .dateTime(NOW)
                .build();
    }

    private static Asset position(Portfolio portfolio, String ticker) {
        return portfolio.getAssets().stream()
                .filter(asset -> asset.getTicker().equals(Ticker.of(ticker)))
                .findFirst().orElseThrow();
    }

    @Test
    void shouldCountARedeliveredPurchaseOnlyOnce() {
        Portfolio portfolio = portfolioWithCash(20_000);

        portfolio.handleExecutedTrade(buy("trade-1", 5, 2_000));
        portfolio.handleExecutedTrade(buy("trade-1", 5, 2_000));

        assertThat(position(portfolio, "XAU").getQuantity())
                .as("five ounces were bought, not ten")
                .isEqualTo(Quantity.of(5));
        assertThat(position(portfolio, "USD").getQuantity())
                .as("and ten thousand was spent, not twenty")
                .isEqualTo(Quantity.of(10_000));
    }

    /** The redelivery is ignored, not refused: the caller asked for something already true. */
    @Test
    void shouldIgnoreTheRepeatWithoutFailing() {
        Portfolio portfolio = portfolioWithCash(20_000);
        portfolio.handleExecutedTrade(buy("trade-1", 1, 2_000));

        portfolio.handleExecutedTrade(buy("trade-1", 1, 2_000));

        assertThat(portfolio.hasAlreadyCounted(TradeId.of("trade-1"))).isTrue();
        assertThat(portfolio.hasAlreadyCounted(TradeId.of("trade-2"))).isFalse();
    }

    /**
     * Since F6 a repeat costs more than a wrong quantity: the settled result is the figure somebody
     * copies into a tax return.
     */
    @Test
    void shouldNotSettleTheSameSaleTwice() {
        Portfolio portfolio = PortfolioFixture.portfolio()
                .at(com.multi.vidulum.common.Broker.of("PM")).denominatedIn("USD")
                .with("XAU", Quantity.of(5), CostBasis.of(
                        Quantity.of(5), Price.of(2_000, "USD"), Provenance.DERIVED_FROM_FILLS))
                .build();

        ExecutedTrade sale = ExecutedTrade.builder()
                .portfolioId(portfolio.getPortfolioId())
                .tradeId(TradeId.of("sale-1"))
                .orderId(OrderId.notDefined())
                .symbol(Symbol.of(Ticker.of("XAU"), Ticker.of("USD")))
                .subName(SubName.traded())
                .side(Side.SELL)
                .quantity(Quantity.of(2))
                .price(Price.of(2_400, "USD"))
                .dateTime(NOW)
                .build();

        portfolio.handleExecutedTrade(sale);
        portfolio.handleExecutedTrade(sale);

        assertThat(portfolio.getRealisedResults())
                .as("one sale, one settled result — not two of 800 each")
                .singleElement()
                .satisfies(settled -> {
                    assertThat(settled.proceeds()).isEqualTo(Money.of(4_800, "USD"));
                    assertThat(settled.cost()).isEqualTo(Money.of(4_000, "USD"));
                });
        assertThat(position(portfolio, "XAU").getQuantity()).isEqualTo(Quantity.of(3));
    }

    /** Two genuinely different trades are two trades, whatever they look like. */
    @Test
    void shouldStillCountTwoDistinctTrades() {
        Portfolio portfolio = portfolioWithCash(20_000);

        portfolio.handleExecutedTrade(buy("trade-1", 1, 2_000));
        portfolio.handleExecutedTrade(buy("trade-2", 1, 2_000));

        assertThat(position(portfolio, "XAU").getQuantity()).isEqualTo(Quantity.of(2));
    }

    /** The memory has to survive storage, or a restart makes every trade fresh again. */
    @Test
    void shouldRememberAcrossTheSnapshotRoundTrip() {
        Portfolio portfolio = portfolioWithCash(20_000);
        portfolio.handleExecutedTrade(buy("trade-1", 1, 2_000));

        Portfolio reloaded = Portfolio.from(portfolio.getSnapshot());
        reloaded.handleExecutedTrade(buy("trade-1", 1, 2_000));

        assertThat(position(reloaded, "XAU").getQuantity()).isEqualTo(Quantity.of(1));
    }
}
