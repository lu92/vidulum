package com.multi.vidulum.trading.app;

import com.multi.vidulum.shared.ddd.event.IntegrationEvent;
import com.multi.vidulum.common.OrderId;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Side;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.TradeId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/**
 * A trade has been recorded. What happens next depends on whether an order preceded it.
 *
 * <p>Internal to trading, and that is why it is here rather than in {@code vidulum-trading-api}:
 * it is published and consumed by this module alone, as the hop on which "is there an order to
 * fill" gets answered. What leaves the module afterwards is {@code TradeConfirmedEvent}.
 *
 * <p>With an order, the fill is applied to it first. Without one — a purchase entered by hand —
 * there is nothing to fill, so the event has to carry everything itself. That is why
 * {@code symbol}, {@code side} and {@code portfolioId} are here: they used to be read off the
 * order.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeCapturedEvent implements IntegrationEvent {

    public static final String TOPIC = "trade_captured";
    public static final String CONTAINER_FACTORY = "tradeCapturedContainerFactory";

    /** {@link OrderId#notDefined()} when the trade was entered by hand. */
    OrderId orderId;
    PortfolioId portfolioId;
    TradeId tradeId;
    Symbol symbol;
    SubName subName;
    Side side;
    Quantity quantity;
    Price price;
    ZonedDateTime dateTime;
}
