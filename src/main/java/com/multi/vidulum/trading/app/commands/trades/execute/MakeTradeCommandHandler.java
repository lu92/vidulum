package com.multi.vidulum.trading.app.commands.trades.execute;

import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.events.TradeCapturedEvent;
import com.multi.vidulum.shared.TradeCapturedEventEmitter;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.trading.domain.*;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class MakeTradeCommandHandler implements CommandHandler<MakeTradeCommand, Trade> {

    private final DomainTradeRepository repository;
    private final DomainOrderRepository orderRepository;
    private final TradeCapturedEventEmitter tradeCapturedEventEmitter;

    @Override
    public Trade handle(MakeTradeCommand command) {

        Order order = orderRepository.findById(command.getOrderId())
                .orElseThrow(() -> new OrderNotFoundException(command.getOrderId()));

        Trade.Fee fee = new Trade.Fee(
                command.getFee().exchangeCurrencyFee(),
                command.getFee().transactionFee(),
                command.getFee().exchangeCurrencyFee().plus(command.getFee().transactionFee())
        );
        Money value = command.getPrice().multiply(command.getQuantity());
        Trade newTrade = Trade.builder()
                .userId(command.getUserId())
                .portfolioId(command.getPortfolioId())
                .originTradeId(command.getOriginTradeId())
                .symbol(order.getSymbol())
                .subName(command.getSubName())
                .side(order.getParameters().side())
                .quantity(command.getQuantity())
                .price(command.getPrice())
                .fee(fee)
                .localValue(value)
                .value(value)
                .totalValue(value.plus(fee.totalFee()))
                .dateTime(command.getOriginDateTime())
                .build();
        Trade savedTrade = repository.save(newTrade);

        // adds execution to order e.g. FillOrderCommand
        tradeCapturedEventEmitter.emit(
                TradeCapturedEvent.builder()
                        .orderId(order.getOrderId())
                        .tradeId(savedTrade.getTradeId())
                        .quantity(savedTrade.getQuantity())
                        .price(savedTrade.getPrice())
                        .dateTime(savedTrade.getDateTime())
                        .build()
        );

        log.info("Trade [{}] has been stored!", savedTrade);
        return savedTrade;
    }
}
