package com.multi.vidulum.trading.app.commands.orders.fill;

import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.events.OrderFilledEvent;
import com.multi.vidulum.shared.OrderFilledEventEmitter;
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
    private final OrderFilledEventEmitter eventEmitter;

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
        log.info("Order [{}]: execution of trade [{}] has been applied successfully!", savedOrder.getOrderId(), command.tradeId());

        OrderFilledEvent event = OrderFilledEvent.builder()
                .orderId(savedOrder.getOrderId())
                .portfolioId(savedOrder.getPortfolioId())
                .tradeId(command.tradeId())
                .symbol(order.getSymbol())
                .subName(SubName.none())
                .side(order.getParameters().side())
                .quantity(command.quantity())
                .price(command.price())
                .build();

        log.info("OrderFilledEvent emitted: [{}]", event);
        eventEmitter.emit(event);
        return null;
    }
}
