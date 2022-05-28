package com.multi.vidulum.portfolio.app.commands.lock;

import com.multi.vidulum.portfolio.domain.PortfolioNotFoundException;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;

@Slf4j
@Component
@AllArgsConstructor
public class LockAssetCommandHandler implements CommandHandler<LockAssetCommand, Portfolio> {

    private final DomainPortfolioRepository repository;
    private final Clock clock;

    @Override
    public Portfolio handle(LockAssetCommand command) {
        Portfolio portfolio = repository
                .findById(command.getPortfolioId())
                .orElseThrow(() -> new PortfolioNotFoundException(command.getPortfolioId()));

        log.info("Attempt to lock [{}] in [{}] - [{}] amount [{}]",
                command.getTicker().getId(),
                command.getPortfolioId(),
                command.getOrderId(),
                command.getQuantity());
        portfolio.lockAsset(command.getTicker(),command.getOrderId(), command.getQuantity(), ZonedDateTime.now(clock));

        Portfolio savedPortfolio = repository.save(portfolio);
        log.info("[{}] Asset [{}] amount [{}] has been locked successfully",
                command.getPortfolioId(),
                command.getTicker(),
                command.getQuantity());
        return savedPortfolio;
    }
}
