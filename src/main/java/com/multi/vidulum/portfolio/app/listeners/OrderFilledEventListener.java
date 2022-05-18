package com.multi.vidulum.portfolio.app.listeners;

import com.multi.vidulum.common.events.OrderFilledEvent;
import com.multi.vidulum.portfolio.app.commands.update.ProcessTradeCommand;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class OrderFilledEventListener {

    private final CommandGateway commandGateway;

    @KafkaListener(
            groupId = "group_id6",
            topics = "order_filled",
            containerFactory = "orderFilledContainerFactory")
    public void on(OrderFilledEvent event) {
        log.info("OrderFilledEvent captured: [{}]", event);

        ProcessTradeCommand command = ProcessTradeCommand.builder()
                .portfolioId(event.getPortfolioId())
                .tradeId(event.getTradeId())
                .symbol(event.getSymbol())
                .subName(event.getSubName())
                .side(event.getSide())
                .quantity(event.getQuantity())
                .price(event.getPrice())
                .build();

        commandGateway.send(command);
    }
}
