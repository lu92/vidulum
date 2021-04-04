package com.multi.vidulum.portfolio.app.commands.update;

import com.multi.vidulum.common.Side;
import com.multi.vidulum.portfolio.app.PortfolioNotFoundException;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.portfolio.domain.QuoteRestClient;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.trades.BuyTrade;
import com.multi.vidulum.portfolio.domain.trades.SellTrade;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

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

        if (isBuying(command)) {
            processBoughtAsset(portfolio, command);
        } else {
            processSoldAsset(portfolio, command);
        }
        return null;
    }

    private boolean isBuying(ApplyTradeCommand command) {
        return command.getSide() == Side.BUY;
    }

    private void processBoughtAsset(Portfolio portfolio, ApplyTradeCommand command) {
        AssetBasicInfo assetBasicInfo = quoteRestClient.fetchBasicInfo(command.getTicker());

        BuyTrade buyTrade = BuyTrade.builder()
                .portfolioId(command.getPortfolioId())
                .tradeId(command.getTradeId())
                .ticker(command.getTicker())
                .quantity(command.getQuantity())
                .price(command.getPrice())
                .build();

        portfolio.handleExecutedTrade(buyTrade, assetBasicInfo);
    }

    private void processSoldAsset(Portfolio portfolio, ApplyTradeCommand command) {
        SellTrade sellTrade = SellTrade.builder()
                .portfolioId(command.getPortfolioId())
                .tradeId(command.getTradeId())
                .ticker(command.getTicker())
                .quantity(command.getQuantity())
                .price(command.getPrice())
                .build();
        portfolio.handleExecutedTrade(sellTrade);
    }
}
