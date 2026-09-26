package com.multi.vidulum.portfolio.app.commands.price;

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
public class StateCostOfPositionCommandHandler
        implements CommandHandler<StateCostOfPositionCommand, Portfolio> {

    private final DomainPortfolioRepository repository;

    @Override
    public Portfolio handle(StateCostOfPositionCommand command) {
        Portfolio portfolio = repository.findById(command.getPortfolioId())
                .orElseThrow(() -> new PortfolioNotFoundException(command.getPortfolioId()));

        portfolio.stateCostOfPosition(
                command.getTicker(), command.getSubName(), command.getAvgPrice(),
                command.getDateTime());

        Portfolio saved = repository.save(portfolio);
        log.info("Portfolio [{}]: owner stated the cost of [{}/{}] as [{}]",
                command.getPortfolioId().getId(), command.getTicker().getId(),
                command.getSubName().getName(), command.getAvgPrice());
        return saved;
    }
}
