package com.multi.vidulum.trading.app.listeners;

import com.multi.vidulum.common.OrderId;
import com.multi.vidulum.common.events.TradeCapturedEvent;
import com.multi.vidulum.portfolio.app.commands.update.ProcessTradeCommand;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.trading.app.commands.orders.fill.FillOrderCommand;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class TradeCapturedEventListener {

    private final CommandGateway commandGateway;

    @KafkaListener(
            groupId = "group_id4",
            topics = "trade_captured",
            containerFactory = "tradeCapturedContainerFactory")
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
            return;
        }

        // Nothing to fill, so the portfolio is reached directly. The longer route exists to keep
        // an order's remaining quantity in step with its fills; with no order there is no such
        // state, and going through it would mean inventing one.
        commandGateway.send(ProcessTradeCommand.builder()
                .portfolioId(event.getPortfolioId())
                .tradeId(event.getTradeId())
                .orderId(OrderId.notDefined())
                .symbol(event.getSymbol())
                .subName(event.getSubName())
                .side(event.getSide())
                .quantity(event.getQuantity())
                .price(event.getPrice())
                .dateTime(event.getDateTime())
                .build());
    }
}
