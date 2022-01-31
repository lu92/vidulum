package com.multi.vidulum.trading.app.commands.orders.create;

import com.multi.vidulum.common.*;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.cqrs.commands.Command;
import lombok.Builder;
import lombok.Value;

import java.time.ZonedDateTime;

@Value
@Builder
public class PlaceOrderCommand implements Command {

    /**
     * sample 1 - STOP-LIMIT
     *
     * [symbol => BTC/USD]
     * [type => STOP_LIMIT]
     * [side => sell]
     * [stopPrice => Price(200, USD)]
     * [limitPrice => Price(190, USD)]
     * [quantity => 0.25]
     * [desc => if the last price rises to or above [200 USD],
     * an order to sell [0.25 BTC] at a price [190 USD] will be placed]
     *
     *
     *
     * sample 2 - OCO
     *
     * [symbol => BTC/USD]
     * [type => OCO]
     * [side => sell]
     * [targetPrice => Price(260, USD)]
     * [stopPrice => Price(200, USD)]
     * [limitPrice => Price(190, USD)]
     * [quantity => 0.25]
     *
     *
     *
     * sample 3 - OCO
     *
     * [symbol => BTC/USD]
     * [type => OCO]
     * [side => buy]
     * [targetPrice => Price(190, USD)]
     * [stopPrice => Price(250, USD)]
     * [limitPrice => Price(160, USD)]
     * [quantity => 0.25]
     */

    OrderId orderId;
    OriginOrderId originOrderId;
    PortfolioId portfolioId;
    Broker broker;
    Symbol symbol;
    OrderType type;
    Side side;
    Price targetPrice;
    Price stopPrice; // price when order will be triggered
    Price limitPrice; // price which will apear in order-book
    Quantity quantity;
    ZonedDateTime occurredDateTime;
}
