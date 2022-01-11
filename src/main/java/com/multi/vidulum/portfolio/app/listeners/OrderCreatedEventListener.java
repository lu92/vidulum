package com.multi.vidulum.portfolio.app.listeners;

import com.multi.vidulum.common.Side;
import com.multi.vidulum.common.events.OrderCreatedEvent;
import com.multi.vidulum.portfolio.app.commands.lock.LockAssetCommand;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class OrderCreatedEventListener {

    private final CommandGateway commandGateway;

    @KafkaListener(
            groupId = "group_id1",
            topics = "order_created",
            containerFactory = "orderCreatedContainerFactory")
    public void on(OrderCreatedEvent event) {
        log.info("OrderCreatedEvent [{}] has been captured", event);

        if (Side.SELL.equals(event.getSide())) {
            lockAssetQuantity(event);
        } else {
            lockBalanceForPurchaseOfAsset(event);
        }
    }

    private void lockAssetQuantity(OrderCreatedEvent event) {
        LockAssetCommand command = LockAssetCommand.builder()
                .portfolioId(event.getPortfolioId())
                .ticker(event.getSymbol().getOrigin())
                .quantity(event.getQuantity())
                .build();
        commandGateway.send(command);
    }

    private void lockBalanceForPurchaseOfAsset(OrderCreatedEvent event) {
        LockAssetCommand command = LockAssetCommand.builder()
                .portfolioId(event.getPortfolioId())
                .ticker(event.getSymbol().getDestination())
                .quantity(event.getQuantity())
                .build();
        commandGateway.send(command);
    }
}
