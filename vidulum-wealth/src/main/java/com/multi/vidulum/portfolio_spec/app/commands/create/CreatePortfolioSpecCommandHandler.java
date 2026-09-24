package com.multi.vidulum.portfolio_spec.app.commands.create;

import com.multi.vidulum.exchange_connection.domain.DomainExchangeConnectionRepository;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnection;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnectionId;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio_spec.domain.ConnectionMismatchException;
import com.multi.vidulum.portfolio_spec.domain.DomainPortfolioSpecRepository;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpec;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpecId;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;

@Slf4j
@Component
@AllArgsConstructor
public class CreatePortfolioSpecCommandHandler
        implements CommandHandler<CreatePortfolioSpecCommand, PortfolioSpec> {

    private final DomainPortfolioSpecRepository specRepository;
    private final DomainPortfolioRepository portfolioRepository;
    private final DomainExchangeConnectionRepository connectionRepository;
    private final Clock clock;

    @Override
    public PortfolioSpec handle(CreatePortfolioSpecCommand command) {
        requireAgreesWithConnection(command);
        List<Asset> knownState = knownStateOf(command);

        PortfolioSpec spec = PortfolioSpec.from(
                PortfolioSpecId.generate(),
                command.userId(),
                command.connectionId(),
                command.denominationCurrency(),
                command.portfolioId(),
                knownState,
                command.snapshot(),
                ZonedDateTime.now(clock));

        PortfolioSpec saved = specRepository.save(spec);
        log.info("Specification [{}] for user [{}]: {} differences, {} needing an answer",
                saved.getId().getId(), command.userId().getId(),
                saved.getDifferences().size(), saved.openQuestions().size());
        return saved;
    }

    /**
     * Confronts what the request says with what the connection says, before anything is computed.
     *
     * <p>{@code confirm} checks the same two fields, but checking them only there is too late to
     * be useful: the currency decides which snapshot line is filed as cash (C10), so a
     * specification built with the wrong one asks the wrong questions, and the caller finds out
     * after answering all of them — at which point no currency is accepted any more, because one
     * check rejects what the connection contradicts and the other rejects what the specification
     * contradicts. Failing on the first request instead leaves a dead end unreachable.
     */
    private void requireAgreesWithConnection(CreatePortfolioSpecCommand command) {
        if (command.connectionId() == null) {
            return;
        }
        ExchangeConnection connection = connectionRepository.findOwnedOrThrow(
                command.userId(), ExchangeConnectionId.of(command.connectionId()));

        requireSame("denominationCurrency",
                command.denominationCurrency().getId(),
                connection.getDenominationCurrency().getId());
        requireSame("broker",
                command.snapshot().broker().getId(),
                connection.getBroker().getId());
    }

    private static void requireSame(String field, String stated, String onConnection) {
        if (!stated.equalsIgnoreCase(onConnection)) {
            throw new ConnectionMismatchException(field, stated, onConnection);
        }
    }

    /**
     * Onboarding is not a special case — it is this same calculation with nothing known yet, so
     * an absent portfolio simply yields an empty list rather than a separate code path.
     */
    private List<Asset> knownStateOf(CreatePortfolioSpecCommand command) {
        if (command.portfolioId() == null) {
            return List.of();
        }
        return portfolioRepository.findById(command.portfolioId())
                .map(Portfolio::getAssets)
                .orElse(List.of());
    }
}
