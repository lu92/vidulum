package com.multi.vidulum.portfolio.app.commands.update;

import com.multi.vidulum.common.StoredTrade;
import com.multi.vidulum.common.events.TradeAppliedToPortfolioEvent;
import com.multi.vidulum.portfolio.domain.PortfolioNotFoundException;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.trades.ExecutedTrade;
import com.multi.vidulum.shared.TradeAppliedToPortfolioEventEmitter;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class ApplyTradeCommandHandler implements CommandHandler<ApplyTradeCommand, Void> {

    private final DomainPortfolioRepository repository;
    private final TradeAppliedToPortfolioEventEmitter eventEmitter;

    @Override
    public Void handle(ApplyTradeCommand command) {
        StoredTrade trade = command.getTrade();
        log.info("Processing [ApplyTradeCommand]: [{}]", trade);

        Portfolio portfolio = repository
                .findById(trade.getPortfolioId())
                .orElseThrow(() -> new PortfolioNotFoundException(trade.getPortfolioId()));

        ExecutedTrade executedTrade = ExecutedTrade.builder()
                .portfolioId(trade.getPortfolioId())
                .tradeId(trade.getTradeId())
                .symbol(trade.getSymbol())
                .subName(trade.getSubName())
                .side(trade.getSide())
                .quantity(trade.getQuantity())
                .price(trade.getPrice())
                .build();

        portfolio.handleExecutedTrade(executedTrade);

        repository.save(portfolio);
        log.info("After processing: [{}]", portfolio);

        emitEvent(trade);

        log.info(String.format("Trade [origin: %s generated: %s] has been applied to portfolio [%s] successfully", trade.getOriginOrderId(), trade.getTradeId(), trade.getPortfolioId()));
        return null;
    }

    private void emitEvent(StoredTrade trade) {
        TradeAppliedToPortfolioEvent event = TradeAppliedToPortfolioEvent.builder()
                .trade(trade)
                .build();
        eventEmitter.emit(event);
    }
}
