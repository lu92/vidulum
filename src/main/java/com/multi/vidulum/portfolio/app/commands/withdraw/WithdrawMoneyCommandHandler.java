package com.multi.vidulum.portfolio.app.commands.withdraw;

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
public class WithdrawMoneyCommandHandler implements CommandHandler<WithdrawMoneyCommand, Void> {
    private final DomainPortfolioRepository repository;


    @Override
    public Void handle(WithdrawMoneyCommand command) {
        Portfolio portfolio = repository.findById(command.getPortfolioId())
                .orElseThrow(() -> new PortfolioNotFoundException(command.getPortfolioId()));

        portfolio.withdrawMoney(command.getMoney());
        repository.save(portfolio);
        log.info("Portfolio [{}]: money [{}}] has been withdrawn", portfolio.getPortfolioId(), command.getMoney());
        return null;
    }
}
