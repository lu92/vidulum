package com.multi.vidulum.portfolio_spec.app.commands.answer;

import com.multi.vidulum.portfolio_spec.domain.DomainPortfolioSpecRepository;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpec;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class AnswerPortfolioSpecCommandHandler
        implements CommandHandler<AnswerPortfolioSpecCommand, PortfolioSpec> {

    private final DomainPortfolioSpecRepository repository;

    @Override
    public PortfolioSpec handle(AnswerPortfolioSpecCommand command) {
        PortfolioSpec spec = repository.findOwnedOrThrow(command.userId(), command.specId());

        command.answers().forEach(given ->
                spec.answer(given.ticker(), given.subName(), given.quantity(), given.answer()));

        PortfolioSpec saved = repository.save(spec);
        log.info("Specification [{}]: {} answer(s) recorded, {} still open",
                command.specId().getId(), command.answers().size(), saved.openQuestions().size());
        return saved;
    }
}
