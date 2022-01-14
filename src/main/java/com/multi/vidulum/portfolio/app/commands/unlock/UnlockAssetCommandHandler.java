package com.multi.vidulum.portfolio.app.commands.unlock;

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
public class UnlockAssetCommandHandler implements CommandHandler<UnlockAssetCommand, Portfolio> {

    private final DomainPortfolioRepository repository;

    @Override
    public Portfolio handle(UnlockAssetCommand command) {
        Portfolio portfolio = repository
                .findById(command.getPortfolioId())
                .orElseThrow(() -> new PortfolioNotFoundException(command.getPortfolioId()));

        portfolio.unlockAsset(command.getTicker(), command.getQuantity());

        Portfolio savedPortfolio = repository.save(portfolio);
        log.info(String.format("[%s] Asset [%s] amount [%s] has been unlocked successfully",
                command.getPortfolioId(),
                command.getTicker(),
                command.getQuantity()));

        return savedPortfolio;
    }
}
