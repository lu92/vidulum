package com.multi.vidulum.trading.app.commands.orders.cancel;

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
public class CancelOrderCommandHandler implements CommandHandler<CancelOrderCommand, Order> {

    private final DomainOrderRepository orderRepository;

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
        return savedOrder;
    }
}
