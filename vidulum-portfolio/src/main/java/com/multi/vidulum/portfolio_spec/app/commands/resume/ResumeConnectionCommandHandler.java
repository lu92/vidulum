package com.multi.vidulum.portfolio_spec.app.commands.resume;

import com.multi.vidulum.exchange_connection.api.ExchangeConnectionApi;
import com.multi.vidulum.exchange_connection.api.view.ConnectionView;
import com.multi.vidulum.portfolio_spec.app.commands.create.CreatePortfolioSpecCommand;
import com.multi.vidulum.portfolio_spec.app.commands.create.CreatePortfolioSpecCommandHandler;
import com.multi.vidulum.portfolio_spec.domain.NothingToSynchroniseException;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpec;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resuming a connection after a break: status back into service, and the portfolio caught up
 * (task H3).
 *
 * <p>Lives in this module rather than beside the connection because it spans both, and the
 * dependency runs one way: {@code vidulum-wealth} knows about {@code vidulum-exchange}, never the
 * reverse. Each half is still driven through its own command, so neither module reaches into the
 * other's repository.
 *
 * <p><b>Resuming is synchronisation, not onboarding.</b> The specification is computed against the
 * portfolio from before the break — a non-empty known state — which is what stops the owner from
 * being asked about their entire portfolio again. A long gap makes the difference bigger, not
 * different in kind; the machinery is the one D13 opened up for a second synchronisation.
 *
 * <p>What this does <b>not</b> do yet: explain the gap. The difference says the balance changed,
 * not whether it changed because something was bought or because something arrived — and those two
 * decide the cost basis and the contribution ledger. That needs the operation history (H5), and
 * until then a resumed account is counted, not accounted for.
 */
@Slf4j
@Component
@AllArgsConstructor
public class ResumeConnectionCommandHandler
        implements CommandHandler<ResumeConnectionCommand, Optional<PortfolioSpec>> {

    private final ExchangeConnectionApi exchangeApi;
    private final CreatePortfolioSpecCommandHandler createSpecHandler;

    /**
     * @return the specification to answer and confirm, or empty when the account did not move
     *         during the break — see {@link #alreadyInAgreement}
     */
    @Override
    public Optional<PortfolioSpec> handle(ResumeConnectionCommand command) {
        // Ownership and state are checked before anything is written: a connection that cannot be
        // resumed must not leave a specification behind.
        ConnectionView connection =
                exchangeApi.ownedOrThrow(command.userId(), command.connectionId());

        exchangeApi.reconnect(command.userId(), command.connectionId());

        try {
            PortfolioSpec spec = createSpecHandler.handle(new CreatePortfolioSpecCommand(
                    command.userId(),
                    command.connectionId().getId(),
                    connection.portfolioId(),
                    connection.denominationCurrency(),
                    command.snapshot()));

            log.info("Connection [{}] resumed with {} difference(s) to settle",
                    command.connectionId().getId(), spec.getDifferences().size());
            return Optional.of(spec);
        } catch (NothingToSynchroniseException nothingChanged) {
            return alreadyInAgreement(command);
        }
    }

    /**
     * The account did not move during the break.
     *
     * <p>No specification — an empty one is a question with no content (D8) — but the
     * synchronisation <b>is</b> recorded, and that is the part worth stating plainly: "already in
     * agreement" is agreement. Treating it as a non-event would leave the anchor pointing at a
     * moment before a break that has since been shown to have changed nothing, and the gap replay
     * would later go looking through history for differences that do not exist.
     */
    private Optional<PortfolioSpec> alreadyInAgreement(ResumeConnectionCommand command) {
        exchangeApi.recordSync(
                command.userId(), command.connectionId(), command.snapshot().takenAt());

        log.info("Connection [{}] resumed; the account is unchanged since the break",
                command.connectionId().getId());
        return Optional.empty();
    }
}
