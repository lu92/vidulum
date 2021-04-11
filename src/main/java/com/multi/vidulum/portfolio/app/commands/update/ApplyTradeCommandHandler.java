package com.multi.vidulum.portfolio.app.commands.update;

import com.multi.vidulum.common.Side;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.portfolio.domain.PortfolioNotFoundException;
import com.multi.vidulum.portfolio.domain.QuoteRestClient;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.trades.BuyTrade;
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

        AssetBasicInfo assetBasicInfo = quoteRestClient.fetchBasicInfoAboutAsset(command.getTicker());

        if (command.getSide() == Side.BUY) {
            BuyTrade trade = BuyTrade.builder()
                    .portfolioId(command.getPortfolioId())
                    .tradeId(command.getTradeId())
                    .ticker(command.getTicker())
                    .quantity(command.getQuantity())
                    .price(command.getPrice())
                    .build();

            portfolio.handleExecutedTrade(trade, assetBasicInfo);
        } else {
            // handle sell-trade
        }

        repository.save(portfolio);
        log.info(String.format("Trade [%s] has been applied to portfolio [%s] sucessfully", command.getTradeId(), command.getPortfolioId()));
        return null;
    }
}
