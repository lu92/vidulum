package com.multi.vidulum.trading.app.commands.orders.fill;

import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.trading.domain.DomainOrderRepository;
import com.multi.vidulum.trading.domain.Order;
import com.multi.vidulum.trading.domain.OrderNotFoundException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class FillOrderCommandHandler implements CommandHandler<FillOrderCommand, Void> {

    private final DomainOrderRepository orderRepository;

    @Override
    public Void handle(FillOrderCommand command) {
        Order order = orderRepository.findById(command.orderId())
                .orElseThrow(() -> new OrderNotFoundException(command.orderId()));

        order.addExecution(
                new Order.OrderExecution(
                        command.tradeId(),
                        command.quantity(),
                        command.price(),
                        command.dateTime()
                )
        );

        Order savedOrder = orderRepository.save(order);
        log.info("Order [{}]: trade [{}] has been applied successfully!", savedOrder.getOrderId(), command.tradeId());
        return null;
    }
}
