package com.multi.vidulum.portfolio.app.commands.close;

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
public class ClosePortfolioCommandHandler implements CommandHandler<ClosePortfolioCommand, Portfolio> {

    private final DomainPortfolioRepository repository;

    @Override
    public Portfolio handle(ClosePortfolioCommand command) {

        Portfolio portfolio = repository.findById(command.getPortfolioId())
                .orElseThrow(() -> new PortfolioNotFoundException(command.getPortfolioId()));

        portfolio.close();

        Portfolio savedPortfolio = repository.save(portfolio);

        log.info("portfolio [{}] has been closed!", savedPortfolio.getPortfolioId());
        return savedPortfolio;
    }
}
