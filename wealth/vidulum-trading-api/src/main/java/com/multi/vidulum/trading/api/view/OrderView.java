package com.multi.vidulum.trading.api.view;

import com.multi.vidulum.common.OriginOrderId;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Side;
import com.multi.vidulum.common.Symbol;

import java.time.ZonedDateTime;

/**
 * An order still open, as much of it as anybody outside trading reads.
 *
 * <p>Eight fields out of the twelve on {@code TradingDto.OrderSummaryJson}. Left out: the internal
 * {@code orderId} (consumers key on {@code originOrderId}, which is what the exchange calls it),
 * {@code portfolioId} (every caller already asked about one portfolio), and {@code type} and
 * {@code status} — an order's kind and its place in its own lifecycle are trading's business, and
 * the only thing a consumer does with this list is filter it by {@link #side()}.
 *
 * <p>Identifiers are value objects rather than strings. Risk was doing
 * {@code OrderId.of(orderSummaryJson.getOriginOrderId())} and
 * {@code Symbol.of(orderSummaryJson.getSymbol())} to undo the Json shape.
 */
public record OrderView(
        OriginOrderId originOrderId,
        Symbol symbol,
        Side side,
        Quantity quantity,
        Price limitPrice,
        Price stopPrice,
        Price targetPrice,
        ZonedDateTime originDateTime) {
}
