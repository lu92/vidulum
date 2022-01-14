package com.multi.vidulum.trading.app.commands.orders.cancel;

import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Status;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.events.AssetUnlockedEvent;
import com.multi.vidulum.shared.AssetUnlockedEventEmitter;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.trading.domain.DomainOrderRepository;
import com.multi.vidulum.trading.domain.Order;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class CancelOrderCommandHandler implements CommandHandler<CancelOrderCommand, Order> {

    private final DomainOrderRepository orderRepository;
    private final AssetUnlockedEventEmitter eventEmitter;

    @Override
    public Order handle(CancelOrderCommand command) {
        Order order = orderRepository.findByOriginOrderId(command.getOriginOrderId())
                .orElseThrow(() -> new IllegalArgumentException(String.format("Order [%s] does not exist!", command.getOriginOrderId())));

        if (!order.isOpen()) {
            throw new IllegalArgumentException(String.format("Order [%s] is not open!", order.getOriginOrderId()));
        }

        order.setStatus(Status.CANCELLED);
        Order savedOrder = orderRepository.save(order);
        log.info("Order [{}] has been cancelled!", order.getOriginOrderId());
        AssetUnlockedEvent event = buildEvent(savedOrder);
        eventEmitter.emit(event);
        log.info("Event [{}] has been emitted", event);
        return savedOrder;
    }

    private AssetUnlockedEvent buildEvent(Order order) {
        Ticker ticker = order.isPurchaseAttempt() ? order.getSymbol().getDestination() : order.getSymbol().getOrigin();
        Quantity quantityToUnlocked = Quantity.of(order.getTotal().getAmount().doubleValue());
        return AssetUnlockedEvent.builder()
                .portfolioId(order.getPortfolioId())
                .ticker(ticker)
                .quantity(quantityToUnlocked)
                .build();
    }
}
