package com.multi.vidulum.trading.api;

import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.Range;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.trading.api.view.OrderView;
import com.multi.vidulum.trading.api.view.TradeView;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * What other modules may ask of trading.
 *
 * <p>Two methods, because two is what portfolio, risk and pnl between them call. Placing,
 * cancelling and executing an order all arrive over HTTP from the owner, and a settled trade leaves
 * as {@link TradeConfirmedEvent} — neither needs a seat here.
 *
 * <p>Previously {@code portfolio.domain.TradingRestClient}: a port for <b>trading</b> declared in
 * <b>portfolio's</b> domain package, with an adapter that injected two of trading's query handlers.
 * Risk and pnl imported it from there, so to obtain trading's contract they depended on a third
 * module. The name lied as its portfolio counterpart did — no HTTP, no client.
 */
public interface TradingModuleApi {

    /** Orders still open on one portfolio. Empty when it has none. */
    List<OrderView> openedOrders(PortfolioId portfolioId);

    /** Everything one owner traded inside the window, across all their portfolios. */
    List<TradeView> tradesInRange(UserId owner, Range<ZonedDateTime> window);
}
