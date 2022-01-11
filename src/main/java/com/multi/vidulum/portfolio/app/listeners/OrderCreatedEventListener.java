package com.multi.vidulum.portfolio.app.listeners;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.OrderType;
import com.multi.vidulum.common.Quantity;
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

        if (isOrderWithPurchaseAttempt(event)) {
            lockBalanceForPurchaseOfAsset(event);
        } else {
            lockAssetForSale(event);
        }
    }

    private boolean isOrderWithPurchaseAttempt(OrderCreatedEvent event) {
        return Side.BUY.equals(event.getSide());
    }

    private void lockAssetForSale(OrderCreatedEvent event) {
        LockAssetCommand command = LockAssetCommand.builder()
                .portfolioId(event.getPortfolioId())
                .ticker(event.getSymbol().getOrigin())
                .quantity(event.getQuantity())
                .build();
        commandGateway.send(command);
    }

    private void lockBalanceForPurchaseOfAsset(OrderCreatedEvent event) {
        Money money = OrderType.OCO.equals(event.getType()) ?
                event.getTargetPrice().multiply(event.getQuantity()) :
                event.getLimitPrice().multiply(event.getQuantity());
        Quantity quantityToLocked = Quantity.of(money.getAmount().doubleValue());

        LockAssetCommand command = LockAssetCommand.builder()
                .portfolioId(event.getPortfolioId())
                .ticker(event.getSymbol().getDestination())
                .quantity(quantityToLocked)
                .build();
        commandGateway.send(command);
    }
}
