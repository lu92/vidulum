package com.multi.vidulum.portfolio.app.listeners;

import com.multi.vidulum.portfolio.app.commands.update.ProcessTradeCommand;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.trading.api.TradeConfirmedEvent;
import com.multi.vidulum.trading.api.TradeConfirmedHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * A settled trade moves the portfolio.
 *
 * <p>Declaring {@link TradeConfirmedHandler} is how this module says what it listens to: the answer
 * to "who consumes a confirmed trade" is the set of implementations of that interface, and the
 * topic and listener factory come from it rather than from strings repeated here.
 *
 * <p>It replaced a listener on {@code order_filled}. A portfolio has no business knowing what an
 * order is — a trade is "so many units of X exchanged for so many of Y at a moment", and whether
 * an exchange order preceded it is trading's concern. The order id still travels, as provenance.
 */
@Slf4j
@Component
@AllArgsConstructor
public class TradeConfirmedToPortfolio implements TradeConfirmedHandler {

    private final CommandGateway commandGateway;

    @Override
    @KafkaListener(groupId = "portfolio", topics = TOPIC, containerFactory = CONTAINER_FACTORY)
    public void onTradeConfirmed(TradeConfirmedEvent event) {
        log.info("TradeConfirmedEvent captured: [{}]", event);

        commandGateway.send(ProcessTradeCommand.builder()
                .portfolioId(event.getPortfolioId())
                .tradeId(event.getTradeId())
                .orderId(event.getOrderId())
                .symbol(event.getSymbol())
                .subName(event.getSubName())
                .side(event.getSide())
                .quantity(event.getQuantity())
                .price(event.getPrice())
                .dateTime(event.getDateTime())
                .build());
    }
}
