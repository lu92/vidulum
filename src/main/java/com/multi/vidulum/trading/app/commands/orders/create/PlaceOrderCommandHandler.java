package com.multi.vidulum.trading.app.commands.orders.create;

import com.multi.vidulum.common.Status;
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
        return savedOrder;
    }
}
