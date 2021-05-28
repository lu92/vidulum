package com.multi.vidulum.portfolio.app.commands.deposit;

import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.portfolio.domain.AssetBasicInfo;
import com.multi.vidulum.portfolio.domain.PortfolioNotFoundException;
import com.multi.vidulum.portfolio.domain.QuoteRestClient;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class DepositMoneyCommandHandler implements CommandHandler<DepositMoneyCommand, Void> {

    private final DomainPortfolioRepository repository;
    private final QuoteRestClient quoteRestClient;

    @Override
    public Void handle(DepositMoneyCommand command) {
        Portfolio portfolio = repository.findById(command.getPortfolioId())
                .orElseThrow(() -> new PortfolioNotFoundException(command.getPortfolioId()));

        Ticker currencyTicker = Ticker.of(command.getMoney().getCurrency());
        AssetBasicInfo assetBasicInfo = quoteRestClient.fetchBasicInfoAboutAsset(portfolio.getBroker(), currencyTicker);
        portfolio.depositMoney(command.getMoney(), assetBasicInfo);
        repository.save(portfolio);
        log.info("Portfolio [{}]: money [{}}] has been deposited", portfolio.getPortfolioId(), command.getMoney());
        return null;
    }
}
