package com.multi.vidulum.portfolio.app.commands.lock;

import com.multi.vidulum.portfolio.domain.PortfolioNotFoundException;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class LockAssetCommandHandler implements CommandHandler<LockAssetCommand, Portfolio> {

    private final DomainPortfolioRepository repository;

    @Override
    public Portfolio handle(LockAssetCommand command) {
        Portfolio portfolio = repository
                .findById(command.getPortfolioId())
                .orElseThrow(() -> new PortfolioNotFoundException(command.getPortfolioId()));

        portfolio.lockAsset(command.getTicker(), command.getQuantity());

        Portfolio savedPortfolio = repository.save(portfolio);
        log.info(String.format("Asset [%s] amount [%s] has been locked successfully in [%s]",
                command.getTicker(),
                command.getQuantity(),
                command.getPortfolioId()));

        return savedPortfolio;
    }
}
