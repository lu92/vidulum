package com.multi.vidulum.user.app.commands.portfolio.register;

import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.user.app.UserNotActiveException;
import com.multi.vidulum.user.app.UserNotFoundException;
import com.multi.vidulum.user.domain.DomainUserRepository;
import com.multi.vidulum.user.domain.PortfolioRestClient;
import com.multi.vidulum.user.domain.User;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class RegisterPortfolioCommandHandler implements CommandHandler<RegisterPortfolioCommand, PortfolioId> {

    private final DomainUserRepository repository;
    private final PortfolioRestClient portfolioRestClient;

    @Override
    public PortfolioId handle(RegisterPortfolioCommand command) {
        User user = repository.findById(command.getUserId())
                .orElseThrow(() -> new UserNotFoundException(command.getUserId()));

        if(!user.isActive()) {
            throw new UserNotActiveException(command.getUserId());
        }

        PortfolioId portfolioId = portfolioRestClient.createPortfolio(
                command.getName(),
                command.getUserId(),
                command.getBroker(),
                command.getCurrency()
        );

        user.registerPortfolio(portfolioId);
        repository.save(user);
        log.info("New portfolio [{}] has been assigned to user [{}]", portfolioId, user.getUserId());
        return portfolioId;
    }
}
