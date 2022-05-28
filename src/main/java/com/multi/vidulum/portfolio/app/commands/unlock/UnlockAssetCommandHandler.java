package com.multi.vidulum.portfolio.app.commands.unlock;

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
public class UnlockAssetCommandHandler implements CommandHandler<UnlockAssetCommand, Portfolio> {

    private final DomainPortfolioRepository repository;
    private final Clock clock;

    @Override
    public Portfolio handle(UnlockAssetCommand command) {
        Portfolio portfolio = repository
                .findById(command.getPortfolioId())
                .orElseThrow(() -> new PortfolioNotFoundException(command.getPortfolioId()));

        log.info("Attempt to unlock [{}] in [{}] - [{}] amount [{}]",
                command.getTicker().getId(),
                command.getPortfolioId(),
                command.getOrderId(),
                command.getQuantity());
        portfolio.unlockAsset(command.getTicker(),command.orderId, command.getQuantity(), ZonedDateTime.now(clock));

        Portfolio savedPortfolio = repository.save(portfolio);
        log.info("[{}] Asset [{}] amount [{}] has been unlocked successfully",
                command.getPortfolioId(),
                command.getTicker(),
                command.getQuantity());
        return savedPortfolio;
    }
}
