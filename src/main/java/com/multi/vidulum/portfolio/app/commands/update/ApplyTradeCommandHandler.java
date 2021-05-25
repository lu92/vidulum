package com.multi.vidulum.portfolio.app.commands.update;

import com.multi.vidulum.common.Side;
import com.multi.vidulum.common.StoredTrade;
import com.multi.vidulum.common.events.TradeAppliedToPortfolioEvent;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.portfolio.domain.PortfolioNotFoundException;
import com.multi.vidulum.portfolio.domain.QuoteRestClient;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.trades.BuyTrade;
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
    private final QuoteRestClient quoteRestClient;
    private final TradeAppliedToPortfolioEventEmitter eventEmitter;

    @Override
    public Void handle(ApplyTradeCommand command) {
        StoredTrade trade = command.getTrade();
        log.info("Processing [ApplyTradeCommand]: [{}]", trade);
        Portfolio portfolio = repository
                .findById(trade.getPortfolioId())
                .orElseThrow(() -> new PortfolioNotFoundException(trade.getPortfolioId()));

        if (trade.getSide() == Side.BUY) {
            handleBuyTrade(trade, portfolio);
        } else {
            handleSellTrade(trade, portfolio);
        }

        repository.save(portfolio);
        log.info("After processing: [{}]", portfolio);

        emitEvent(trade);

        log.info(String.format("Trade [%s] has been applied to portfolio [%s] successfully", trade.getTradeId(), trade.getPortfolioId()));
        return null;
    }

    private void handleBuyTrade(StoredTrade trade, Portfolio portfolio) {
        BuyTrade buyTrade = BuyTrade.builder()
                .portfolioId(trade.getPortfolioId())
                .tradeId(trade.getTradeId())
                .name(trade.getName())
                .symbol(trade.getSymbol())
                .quantity(trade.getQuantity())
                .price(trade.getPrice())
                .build();
        AssetBasicInfo assetBasicInfo = quoteRestClient.fetchBasicInfoAboutAsset(portfolio.getBroker(), trade.getSymbol().getOrigin());
        portfolio.handleExecutedTrade(buyTrade, assetBasicInfo);
    }

    private void handleSellTrade(StoredTrade trade, Portfolio portfolio) {
        SellTrade sellTrade = SellTrade.builder()
                .portfolioId(trade.getPortfolioId())
                .tradeId(trade.getTradeId())
                .name(trade.getName())
                .symbol(trade.getSymbol())
                .quantity(trade.getQuantity())
                .price(trade.getPrice())
                .build();
        AssetBasicInfo assetBasicInfo = quoteRestClient.fetchBasicInfoAboutAsset(portfolio.getBroker(), trade.getSymbol().getDestination());
        portfolio.handleExecutedTrade(sellTrade, assetBasicInfo);
    }

    private void emitEvent(StoredTrade trade) {
        TradeAppliedToPortfolioEvent event = TradeAppliedToPortfolioEvent.builder()
                .trade(trade)
                .build();
        eventEmitter.emit(event);
    }
}
