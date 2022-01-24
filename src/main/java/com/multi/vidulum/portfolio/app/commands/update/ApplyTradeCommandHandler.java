package com.multi.vidulum.portfolio.app.commands.update;

import com.multi.vidulum.common.Side;
import com.multi.vidulum.common.StoredTrade;
import com.multi.vidulum.common.events.TradeAppliedToPortfolioEvent;
import com.multi.vidulum.portfolio.domain.PortfolioNotFoundException;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.trades.BuyTrade;
import com.multi.vidulum.portfolio.domain.trades.ExecutedTradeEvent;
import com.multi.vidulum.portfolio.domain.trades.SellTrade;
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

        ExecutedTradeEvent executedTrade = ExecutedTradeEvent.builder()
                .portfolioId(trade.getPortfolioId())
                .tradeId(trade.getTradeId())
                .symbol(trade.getSymbol())
                .subName(trade.getSubName())
                .side(trade.getSide())
                .quantity(trade.getQuantity())
                .price(trade.getPrice())
                .build();

        if (trade.getSide() == Side.BUY) {
//            handleBuyTrade(trade, portfolio);
            handleTrade(portfolio, executedTrade);
        } else {
            handleSellTrade(trade, portfolio);
//            handleTrade(portfolio, executedTrade);
        }


//        handleTrade(portfolio, executedTrade);


        repository.save(portfolio);
        log.info("After processing: [{}]", portfolio);

        emitEvent(trade);

        log.info(String.format("Trade [origin: %s generated: %s] has been applied to portfolio [%s] successfully", trade.getOriginOrderId(), trade.getTradeId(), trade.getPortfolioId()));
        return null;
    }

    private void handleTrade(Portfolio portfolio, ExecutedTradeEvent trade) {
        portfolio.handleExecutedTrade(trade);
    }

    private void handleBuyTrade(StoredTrade trade, Portfolio portfolio) {
        BuyTrade buyTrade = BuyTrade.builder()
                .portfolioId(trade.getPortfolioId())
                .tradeId(trade.getTradeId())
                .subName(trade.getSubName())
                .symbol(trade.getSymbol())
                .quantity(trade.getQuantity())
                .price(trade.getPrice())
                .build();
        portfolio.handleExecutedTrade(buyTrade);
    }

    private void handleSellTrade(StoredTrade trade, Portfolio portfolio) {
        SellTrade sellTrade = SellTrade.builder()
                .portfolioId(trade.getPortfolioId())
                .tradeId(trade.getTradeId())
                .subName(trade.getSubName())
                .symbol(trade.getSymbol())
                .quantity(trade.getQuantity())
                .price(trade.getPrice())
                .build();
        portfolio.handleExecutedTrade(sellTrade);
    }

    private void emitEvent(StoredTrade trade) {
        TradeAppliedToPortfolioEvent event = TradeAppliedToPortfolioEvent.builder()
                .trade(trade)
                .build();
        eventEmitter.emit(event);
    }
}
