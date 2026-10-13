package com.multi.vidulum.trading.api.view;

import com.multi.vidulum.common.OriginTradeId;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Side;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.TradeId;

import java.time.ZonedDateTime;

/**
 * A recorded trade, as much of it as anybody outside trading reads.
 *
 * <p>Nine fields out of the ten on {@code TradingDto.TradeSummaryJson} — only {@code userId} is
 * left out, because the one caller asked about a user to get this list in the first place.
 *
 * <p><b>No fee.</b> Trading knows what a trade cost in commission and the portfolio does not; that
 * gap is a task of its own (`2026-10-13-fee-in-cost-basis.md`) and it travels on
 * {@code TradeConfirmedEvent}, not here — this view is read by pnl to record what was traded, not
 * to price it.
 */
public record TradeView(
        TradeId tradeId,
        OriginTradeId originTradeId,
        PortfolioId portfolioId,
        Symbol symbol,
        SubName subName,
        Side side,
        Quantity quantity,
        Price price,
        ZonedDateTime originDateTime) {
}
