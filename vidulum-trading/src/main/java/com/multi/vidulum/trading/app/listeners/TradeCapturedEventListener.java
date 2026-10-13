package com.multi.vidulum.trading.app.listeners;

import com.multi.vidulum.common.OrderId;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.trading.api.TradeConfirmedEvent;
import com.multi.vidulum.trading.app.TradeCapturedEvent;
import com.multi.vidulum.trading.app.commands.orders.fill.FillOrderCommand;
import com.multi.vidulum.trading.infrastructure.TradeConfirmedEventEmitter;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Where trading decides what a recorded trade means, and the only place that decides it.
 *
 * <p>Both branches end with {@code TradeConfirmedEvent} leaving the module. Until this change the
 * branch without an order built {@code ProcessTradeCommand} — the <b>portfolio's</b> command — and
 * sent it through the shared gateway. That gave one business operation two routes, one of which had
 * trading deciding another module's business, and it was the last crossing left after the portfolio
 * contract was introduced.
 */
@Slf4j
@Component
@AllArgsConstructor
public class TradeCapturedEventListener {

    private final CommandGateway commandGateway;
    private final TradeConfirmedEventEmitter tradeConfirmedEventEmitter;

    @KafkaListener(
            groupId = "trading",
            topics = TradeCapturedEvent.TOPIC,
            containerFactory = TradeCapturedEvent.CONTAINER_FACTORY)
    public void on(TradeCapturedEvent event) {
        log.info("TradeCapturedEvent [{}] has been captured", event);

        // The routing decision belongs here rather than in the handler that emitted the event:
        // one entry point, one event, and the question "is there an order to fill" answered once.
        if (OrderId.isDefined(event.getOrderId())) {
            commandGateway.send(new FillOrderCommand(
                    event.getOrderId(),
                    event.getTradeId(),
                    event.getQuantity(),
                    event.getPrice(),
                    event.getDateTime()));
            // FillOrderCommandHandler confirms the trade once the fill has been applied.
            return;
        }

        // Nothing to fill, so the trade is confirmed as it stands. The longer route exists to keep
        // an order's remaining quantity in step with its fills; with no order there is no such
        // state, and going through it would mean inventing one.
        tradeConfirmedEventEmitter.emit(TradeConfirmedEvent.builder()
                .orderId(OrderId.notDefined())
                .portfolioId(event.getPortfolioId())
                .tradeId(event.getTradeId())
                .symbol(event.getSymbol())
                .subName(event.getSubName())
                .side(event.getSide())
                .quantity(event.getQuantity())
                .price(event.getPrice())
                .dateTime(event.getDateTime())
                .build());
    }
}
