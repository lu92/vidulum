package com.multi.vidulum.portfolio.app.commands.update;

import com.multi.vidulum.portfolio.domain.PortfolioNotFoundException;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.trades.ExecutedTrade;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class ProcessTradeCommandHandler implements CommandHandler<ProcessTradeCommand, Void> {

    private final DomainPortfolioRepository repository;

    @Override
    public Void handle(ProcessTradeCommand command) {
        log.info("[{}] Processing [{}]: details [{}]", command.getPortfolioId(), command.getTradeId(),
                String.format("%s %s %s %s", command.getSymbol().getId(), command.getSide(), command.quantity, command.getPrice()));

        Portfolio portfolio = repository
                .findById(command.getPortfolioId())
                .orElseThrow(() -> new PortfolioNotFoundException(command.getPortfolioId()));

        ExecutedTrade executedTrade = ExecutedTrade.builder()
                .portfolioId(command.getPortfolioId())
                .tradeId(command.getTradeId())
                .symbol(command.getSymbol())
                .subName(command.getSubName())
                .side(command.getSide())
                .quantity(command.getQuantity())
                .price(command.getPrice())
                .build();

        portfolio.handleExecutedTrade(executedTrade);
        log.info("[{}] processed [{}]: details [{}]", command.getPortfolioId(), command.getTradeId(),
                String.format("%s %s %s %s", command.getSymbol().getId(), command.getSide(), command.quantity, command.getPrice()));

        repository.save(portfolio);
        return null;
    }
}
