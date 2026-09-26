package com.multi.vidulum.portfolio_spec.app.commands.answer;

import com.multi.vidulum.portfolio_spec.domain.DomainPortfolioSpecRepository;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpec;
import com.multi.vidulum.portfolio_spec.domain.SnapshotExpiredException;
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

        try {
            command.answers().forEach(given ->
                    spec.answer(given.ticker(), given.subName(), given.quantity(), given.answer(),
                            command.dateTime()));
        } catch (SnapshotExpiredException expired) {
            // The refusal also decides something: this specification is stale and the owner has to
            // read the account again. Saved before rethrowing, or the status would live only in
            // this object and die with the request — the next GET would show AWAITING_ANSWER and
            // hand them the same form the answer was just refused against.
            repository.save(spec);
            throw expired;
        }

        PortfolioSpec saved = repository.save(spec);
        log.info("Specification [{}]: {} answer(s) recorded, {} still open",
                command.specId().getId(), command.answers().size(), saved.openQuestions().size());
        return saved;
    }
}
