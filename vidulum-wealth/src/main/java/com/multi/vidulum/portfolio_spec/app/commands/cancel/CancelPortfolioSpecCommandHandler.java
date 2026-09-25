package com.multi.vidulum.portfolio_spec.app.commands.cancel;

import com.multi.vidulum.portfolio_spec.domain.DomainPortfolioSpecRepository;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpec;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class CancelPortfolioSpecCommandHandler
        implements CommandHandler<CancelPortfolioSpecCommand, PortfolioSpec> {

    private final DomainPortfolioSpecRepository repository;

    @Override
    public PortfolioSpec handle(CancelPortfolioSpecCommand command) {
        PortfolioSpec spec = repository.findOwnedOrThrow(command.userId(), command.specId());
        spec.cancel();
        PortfolioSpec saved = repository.save(spec);
        log.info("Specification [{}] cancelled with {} question(s) still open",
                command.specId().getId(), saved.openQuestions().size());
        return saved;
    }
}
