package com.multi.vidulum.portfolio.app.commands.update;

import com.multi.vidulum.common.Side;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.portfolio.domain.PortfolioNotFoundException;
import com.multi.vidulum.portfolio.domain.QuoteRestClient;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.trades.BuyTrade;
import com.multi.vidulum.portfolio.domain.trades.SellTrade;
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

    @Override
    public Void handle(ApplyTradeCommand command) {

        Portfolio portfolio = repository
                .findById(command.getPortfolioId())
                .orElseThrow(() -> new PortfolioNotFoundException(command.getPortfolioId()));

        if (command.getSide() == Side.BUY) {
            handleBuyTrade(command, portfolio);
        } else {
            handleSellTrade(command, portfolio);
        }

        repository.save(portfolio);
        log.info(String.format("Trade [%s] has been applied to portfolio [%s] successfully", command.getTradeId(), command.getPortfolioId()));
        return null;
    }

    private void handleBuyTrade(ApplyTradeCommand command, Portfolio portfolio) {
        BuyTrade trade = BuyTrade.builder()
                .portfolioId(command.getPortfolioId())
                .tradeId(command.getTradeId())
                .symbol(command.getSymbol())
                .quantity(command.getQuantity())
                .price(command.getPrice())
                .build();
        AssetBasicInfo assetBasicInfo = quoteRestClient.fetchBasicInfoAboutAsset(portfolio.getBroker(), command.getSymbol().getOrigin());
        portfolio.handleExecutedTrade(trade, assetBasicInfo);
    }

    private void handleSellTrade(ApplyTradeCommand command, Portfolio portfolio) {
        SellTrade sellTrade = SellTrade.builder()
                .portfolioId(command.getPortfolioId())
                .tradeId(command.getTradeId())
                .symbol(command.getSymbol())
                .quantity(command.getQuantity())
                .price(command.getPrice())
                .build();
        AssetBasicInfo assetBasicInfo = quoteRestClient.fetchBasicInfoAboutAsset(portfolio.getBroker(), command.getSymbol().getDestination());
        portfolio.handleExecutedTrade(sellTrade, assetBasicInfo);
    }
}
