package com.multi.vidulum.trading.app.commands.trades.execute;

import com.multi.vidulum.common.StoredTrade;
import com.multi.vidulum.common.events.TradeCapturedEvent;
import com.multi.vidulum.common.events.TradeStoredEvent;
import com.multi.vidulum.shared.TradeCapturedEventEmitter;
import com.multi.vidulum.shared.TradeStoredEventEmitter;
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
    private final TradeStoredEventEmitter eventEmitter;
    private final TradeCapturedEventEmitter tradeCapturedEventEmitter;

    @Override
    public Trade handle(MakeTradeCommand command) {

        Order order = orderRepository.findById(command.getOrderId())
                .orElseThrow(() -> new OrderNotFoundException(command.getOrderId()));

        Trade newTrade = Trade.builder()
                .userId(command.getUserId())
                .portfolioId(command.getPortfolioId())
                .originTradeId(command.getOriginTradeId())
                .symbol(order.getSymbol())
                .subName(command.getSubName())
                .side(order.getParameters().side())
                .quantity(command.getQuantity())
                .price(command.getPrice())
                .dateTime(command.getOriginDateTime())
                .build();
        Trade savedTrade = repository.save(newTrade);

//        order.addExecution(
//                new Order.OrderExecution(
//                        savedTrade.getTradeId(),
//                        command.getQuantity(),
//                        command.getPrice(),
//                        command.getOriginDateTime()
//                )
//        );
//
//        Order savedOrder = orderRepository.save(order);

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
        StoredTrade storedTrade = mapToTrade(savedTrade);
        TradeStoredEvent event = TradeStoredEvent.builder()
                .trade(storedTrade)
                .build();
        eventEmitter.emit(event);
        log.info("Event [{}] has been emitted", event);
        return savedTrade;
    }

    private StoredTrade mapToTrade(Trade savedTrade) {
        return StoredTrade.builder()
                .tradeId(savedTrade.getTradeId())
                .originTradeId(savedTrade.getOriginTradeId())
                .userId(savedTrade.getUserId())
                .originTradeId(savedTrade.getOriginTradeId())
                .portfolioId(savedTrade.getPortfolioId())
                .symbol(savedTrade.getSymbol())
                .subName(savedTrade.getSubName())
                .side(savedTrade.getSide())
                .quantity(savedTrade.getQuantity())
                .price(savedTrade.getPrice())
                .dateTime(savedTrade.getDateTime())
                .build();
    }
}
