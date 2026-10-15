package com.multi.vidulum.trading.api;

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
 * A trade is settled as far as trading is concerned, and should now move a portfolio.
 *
 * <p>Published once per trade, at the end of trading's own routing: after the fill has been applied
 * to its order, or straight away when the trade was entered by hand and there is no order to fill.
 * One event for both routes is the point — it is what lets a portfolio stop knowing what an order
 * is.
 *
 * <p>It replaced two events with identical payloads and a crossing that was neither. {@code
 * OrderFilledEvent} told the portfolio about an <b>order</b>, which is trading's concern and not
 * the portfolio's; and for a trade without an order, trading built {@code ProcessTradeCommand} —
 * the portfolio's own command — and sent it through the shared gateway, so one business operation
 * had two routes and one of them decided another module's business.
 *
 * @param orderId {@link OrderId#notDefined()} for a trade entered by hand. Carried as provenance:
 *                the portfolio records which order a holding came from, which is not the same as
 *                knowing what an order does.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeConfirmedEvent {
    OrderId orderId;
    PortfolioId portfolioId;
    TradeId tradeId;
    Symbol symbol;
    SubName subName;
    Side side;
    Quantity quantity;
    Price price;
    /** When the trade happened — a settled result is dated by it (task F6). */
    ZonedDateTime dateTime;
}
