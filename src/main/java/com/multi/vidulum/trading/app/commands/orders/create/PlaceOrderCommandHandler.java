package com.multi.vidulum.trading.app.commands.orders.create;

import com.multi.vidulum.common.Status;
import com.multi.vidulum.common.events.OrderCreatedEvent;
import com.multi.vidulum.shared.OrderCreatedEventEmitter;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.trading.domain.DomainOrderRepository;
import com.multi.vidulum.trading.domain.Order;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class PlaceOrderCommandHandler implements CommandHandler<PlaceOrderCommand, Order> {

    private final DomainOrderRepository orderRepository;
    private final OrderCreatedEventEmitter eventEmitter;

    @Override
    public Order handle(PlaceOrderCommand command) {
        Order order = Order.builder()
                .originOrderId(command.getOriginOrderId())
                .portfolioId(command.getPortfolioId())
                .broker(command.getBroker())
                .symbol(command.getSymbol())
                .type(command.getType())
                .side(command.getSide())
                .targetPrice(command.getTargetPrice())
                .entryPrice(command.getEntryPrice())
                .stopLoss(command.getStopLoss())
                .quantity(command.getQuantity())
                .occurredDateTime(command.getOccurredDateTime())
                .status(Status.OPEN)
                .build();

        Order savedOrder = orderRepository.save(order);
        log.info("Order [{}] has been stored!", savedOrder);

        OrderCreatedEvent event = buildEvent(order);
        eventEmitter.emit(event);
        log.info("Event [{}] has been emitted", event);
        return savedOrder;
    }

    private OrderCreatedEvent buildEvent(Order order) {
        return OrderCreatedEvent.builder()
                .orderId(order.getOrderId())
                .originOrderId(order.getOriginOrderId())
                .portfolioId(order.getPortfolioId())
                .broker(order.getBroker())
                .symbol(order.getSymbol())
                .type(order.getType())
                .side(order.getSide())
                .targetPrice(order.getTargetPrice())
                .entryPrice(order.getEntryPrice())
                .stopLoss(order.getStopLoss())
                .quantity(order.getQuantity())
                .occurredDateTime(order.getOccurredDateTime())
                .riskRewardRatio(order.getRiskRewardRatio())
                .status(order.getStatus())
                .build();
    }
}
