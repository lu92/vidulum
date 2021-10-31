package com.multi.vidulum.trading.app.commands;

import com.multi.vidulum.common.Status;
import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.trading.domain.DomainOrderRepository;
import com.multi.vidulum.trading.domain.Order;
import com.multi.vidulum.trading.domain.Trade;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class ExecuteOrderCommandHandler implements CommandHandler<ExecuteOrderCommand, Void> {
    private final DomainOrderRepository orderRepository;
    private final MakeTradeCommandHandler makeTradeCommandHandler;

    @Override
    public Void handle(ExecuteOrderCommand command) {
        Order order = orderRepository.findByOriginOrderId(command.getOriginOrderId())
                .orElseThrow(() -> new IllegalArgumentException(String.format("Order [%s] does not exist!", command.getOriginOrderId())));

        if (!order.isOpen()) {
            throw new IllegalArgumentException(String.format("Order [%s] is not open!", order.getOriginOrderId()));
        }

        MakeTradeCommand makeTradeCommand = MakeTradeCommand.builder()
                .userId(UserId.of(""))
                .portfolioId(order.getPortfolioId())
                .originTradeId(command.getOriginTradeId())
                .originOrderId(command.getOriginOrderId())
                .symbol(order.getSymbol())
                .subName(SubName.none())
                .side(order.getSide())
                .quantity(order.getQuantity())
                .price(order.getTargetPrice())
                .originDateTime(command.getOriginDateTime())
                .build();

        Trade executedTrade = makeTradeCommandHandler.handle(makeTradeCommand);

        order.setStatus(Status.EXECUTED);
        orderRepository.save(order);

        return null;
    }
}
