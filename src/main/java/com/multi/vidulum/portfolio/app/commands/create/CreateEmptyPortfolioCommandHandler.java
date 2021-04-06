package com.multi.vidulum.portfolio.app.commands.create;

import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioFactory;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class CreateEmptyPortfolioCommandHandler implements CommandHandler<CreateEmptyPortfolioCommand, Portfolio> {

    private final DomainPortfolioRepository repository;
    private final PortfolioFactory portfolioFactory;

    @Override
    public Portfolio handle(CreateEmptyPortfolioCommand command) {
        Portfolio emptyPortfolio = portfolioFactory.createEmptyPortfolio(command.getName(), command.getUserId());
        Portfolio savedPortfolio = repository.save(emptyPortfolio);
        log.info("New portfolio [{}] for user [{}] has been created!", savedPortfolio.getPortfolioId(), savedPortfolio.getUserId());
        return savedPortfolio;
    }
}
