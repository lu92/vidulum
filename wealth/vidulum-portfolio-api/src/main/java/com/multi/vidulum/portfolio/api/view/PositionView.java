package com.multi.vidulum.portfolio.api.view;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.Ticker;

/**
 * One holding, as much of it as anybody outside the portfolio module reads.
 *
 * <p>Seven fields out of the sixteen on {@code PortfolioDto.AssetSummaryJson}: trading needs
 * {@link #free()} to decide whether an order can be placed, risk needs the quantity, the price and
 * the value to work out what a stop-loss protects, and {@link #avgPurchasePrice()} is what it
 * reports as the average purchase price. The rest — locks, coverage ratios, profit percentages,
 * tags, the two "we could not price this" flags — are answers to the owner's own questions and
 * stay behind {@code GET /portfolio}.
 *
 * @param subName which position this is, where one ticker is held in several (task C8). No
 *                consumer distinguishes them yet; a position view that could not say which
 *                position it was would be the wrong thing to hand them when one starts.
 * @param avgPurchasePrice null when the position has no stated or derived cost
 */
public record PositionView(
        Ticker ticker,
        SubName subName,
        Quantity quantity,
        Quantity free,
        Price currentPrice,
        Money currentValue,
        Price avgPurchasePrice) {
}
