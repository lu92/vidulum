package com.multi.vidulum.trading.app.commands;

import com.multi.vidulum.common.StoredTrade;
import com.multi.vidulum.common.events.TradeStoredEvent;
import com.multi.vidulum.shared.TradeStoredEventEmitter;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.trading.domain.DomainTradeRepository;
import com.multi.vidulum.trading.domain.Trade;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class MakeTradeCommandHandler implements CommandHandler<MakeTradeCommand, Trade> {

    private final DomainTradeRepository repository;
    private final TradeStoredEventEmitter eventEmitter;

    @Override
    public Trade handle(MakeTradeCommand command) {
        Trade newTrade = Trade.builder()
                .userId(command.getUserId())
                .portfolioId(command.getPortfolioId())
                .originTradeId(command.getOriginTradeId())
                .symbol(command.getSymbol())
                .subName(command.getSubName())
                .side(command.getSide())
                .quantity(command.getQuantity())
                .price(command.getPrice())
                .dateTime(command.getOriginDateTime())
                .build();

        Trade savedTrade = repository.save(newTrade);
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
