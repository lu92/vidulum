package com.multi.vidulum.trading.app.commands.orders.execute;

import com.multi.vidulum.common.SubName;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.trading.app.commands.trades.execute.MakeTradeCommand;
import com.multi.vidulum.trading.app.commands.trades.execute.MakeTradeCommandHandler;
import com.multi.vidulum.trading.domain.DomainOrderRepository;
import com.multi.vidulum.trading.domain.Order;
import com.multi.vidulum.trading.domain.Trade;
import com.multi.vidulum.user.domain.PortfolioRestClient;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class ExecuteOrderCommandHandler implements CommandHandler<ExecuteOrderCommand, OrderExecutionSummary> {
    private final DomainOrderRepository orderRepository;
    private final MakeTradeCommandHandler makeTradeCommandHandler;
    private final PortfolioRestClient portfolioRestClient;

    @Override
    public OrderExecutionSummary handle(ExecuteOrderCommand command) {
        Order order = orderRepository.findByOriginOrderId(command.getOriginOrderId())
                .orElseThrow(() -> new IllegalArgumentException(String.format("Order [%s] does not exist!", command.getOriginOrderId())));

        if (!order.isOpen()) {
            throw new IllegalArgumentException(String.format("Order [%s] is not open!", order.getOriginOrderId()));
        }

        PortfolioDto.PortfolioSummaryJson portfolio = portfolioRestClient.getPortfolio(order.getPortfolioId());

        MakeTradeCommand makeTradeCommand = MakeTradeCommand.builder()
                .userId(UserId.of(portfolio.getUserId()))
                .portfolioId(order.getPortfolioId())
                .originTradeId(command.getOriginTradeId())
                .orderId(order.getOrderId())
                .subName(SubName.none())
                .quantity(command.getQuantity())
                .price(command.getPrice())
                .fee(new MakeTradeCommand.Fee(
                        command.getExchangeCurrencyFee(),
                        command.getTransactionFee()))
                .originDateTime(command.getOriginDateTime())
                .build();

        Trade executedTrade = makeTradeCommandHandler.handle(makeTradeCommand);

        log.info("Order [{}] in trade [{}] has been executed successfully - target price has been achieved", order.getOriginOrderId(), executedTrade.getOriginTradeId());
        return OrderExecutionSummary.builder()
                .originOrderId(order.getOriginOrderId())
                .originTradeId(executedTrade.getOriginTradeId())
                .symbol(order.getSymbol())
                .type(order.getParameters().type())
                .side(order.getParameters().side())
                .quantity(order.getParameters().quantity())
//                .profit(Money.of(100, "USD"))
                .profit(order.getParameters().targetPrice().minus(order.getParameters().stopPrice()).multiply(order.getParameters().quantity()))
                .build();
    }
}
