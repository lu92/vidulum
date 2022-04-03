package com.multi.vidulum.trading.app.listeners;

import com.multi.vidulum.common.events.TradeCapturedEvent;
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
            groupId = "group_id1",
            topics = "trade_captured",
            containerFactory = "tradeCapturedContainerFactory")
    public void on(TradeCapturedEvent event) {
        log.info("TradeCapturedEvent [{}] has been captured", event);

        FillOrderCommand command = new FillOrderCommand(
                event.getOrderId(),
                event.getTradeId(),
                event.getQuantity(),
                event.getPrice(),
                event.getDateTime()
        );

        commandGateway.send(command);
    }
}
