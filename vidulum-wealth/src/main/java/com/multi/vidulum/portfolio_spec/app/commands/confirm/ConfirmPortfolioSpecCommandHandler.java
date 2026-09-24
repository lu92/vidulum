package com.multi.vidulum.portfolio_spec.app.commands.confirm;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.exchange_connection.app.commands.confirm.ConfirmExchangeConnectionCommand;
import com.multi.vidulum.exchange_connection.app.commands.confirm.ConfirmExchangeConnectionCommandHandler;
import com.multi.vidulum.exchange_connection.domain.DomainExchangeConnectionRepository;
import com.multi.vidulum.exchange_connection.domain.ConnectionStatus;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnectionId;
import com.multi.vidulum.exchange_connection.domain.IllegalConnectionTransitionException;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Symbol;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.portfolio.domain.QuoteRestClient;
import com.multi.vidulum.portfolio.domain.portfolio.Contribution;
import com.multi.vidulum.portfolio.domain.portfolio.Asset;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import com.multi.vidulum.portfolio.domain.portfolio.Portfolio;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioFactory;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnection;
import com.multi.vidulum.portfolio_spec.domain.CannotApplySpecToExistingPortfolioException;
import com.multi.vidulum.portfolio_spec.domain.ConnectionMismatchException;
import com.multi.vidulum.portfolio_spec.domain.Difference;
import com.multi.vidulum.portfolio_spec.domain.DifferenceDirection;
import com.multi.vidulum.portfolio_spec.domain.DomainPortfolioSpecRepository;
import com.multi.vidulum.portfolio_spec.domain.ExchangeSnapshot;
import com.multi.vidulum.portfolio_spec.domain.PortfolioSpec;
import com.multi.vidulum.portfolio_spec.domain.SnapshotChangedException;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.List;

/**
 * Applies a settled specification (task D3).
 *
 * <p>Three steps, in this order and for a reason:
 *
 * <ol>
 *   <li><b>Compare against a fresh snapshot.</b> Always, regardless of the specification's age —
 *       time only approximates the question we care about (§4.5). If the exchange has moved on,
 *       the specification is recomputed and goes back for review rather than being applied to a
 *       world that no longer exists.
 *   <li><b>Create the portfolio</b> from the settled differences, each position carrying its own
 *       cost or none at all.
 *   <li><b>Confirm the connection</b>, which is what makes it start serving synchronisation. Until
 *       this step existed, {@code ExchangeConnection.confirm} had no production caller at all and
 *       {@code reconnect} could never succeed, because nothing could reach {@code REVOKED}.
 * </ol>
 */
@Slf4j
@Component
@AllArgsConstructor
public class ConfirmPortfolioSpecCommandHandler
        implements CommandHandler<ConfirmPortfolioSpecCommand, PortfolioSpec> {

    private final DomainPortfolioSpecRepository specRepository;
    private final DomainExchangeConnectionRepository connectionRepository;
    private final DomainPortfolioRepository portfolioRepository;
    private final PortfolioFactory portfolioFactory;

    /**
     * New here, and the one structural consequence of C12. Creating the portfolio used to need no
     * prices at all — valuation happened later, at {@code GET /portfolio}. An opening contribution
     * states what the account was worth <b>on the day it arrived</b>, so the prices have to be at
     * hand now. Deferring it would record the price of whenever somebody first looked, which is a
     * different number and a non-deterministic one.
     *
     * <p>The data was already required: E8 makes quotes precede onboarding, because without them
     * the first read throws. What changes is <b>when a missing quote is felt</b> — confirmation
     * fails instead of the first read. That is the better failure: no portfolio beats one that
     * cannot be read.
     */
    private final QuoteRestClient quoteRestClient;
    /**
     * The exchange module's handler, injected directly rather than reached through
     * {@code CommandGateway}.
     *
     * <p>The gateway is built from {@code List<CommandHandler>}, so a handler that injects it
     * creates a circular dependency — the context fails to start, and no component test wiring
     * its own gateway would ever notice. The rule that a connection may be confirmed once still
     * lives with the aggregate that owns it; only the delivery is direct.
     */
    private final ConfirmExchangeConnectionCommandHandler confirmConnectionHandler;

    @Override
    public PortfolioSpec handle(ConfirmPortfolioSpecCommand command) {
        PortfolioSpec spec = specRepository.findOwnedOrThrow(command.userId(), command.specId());
        ZonedDateTime now = command.dateTime();

        if (spec.getPortfolioId() != null) {
            throw new CannotApplySpecToExistingPortfolioException(spec.getPortfolioId());
        }

        // Checked before anything is written. There is no transaction spanning the portfolio and
        // the connection, so a connection that cannot be confirmed must stop the operation here
        // rather than after a portfolio exists - otherwise a failure leaves a portfolio behind
        // with a connection still PENDING, and nothing to tell them apart from a real one.
        Optional<ExchangeConnection> connection = confirmableConnection(command, spec);

        if (movedOn(spec.getSnapshot(), command.freshSnapshot())) {
            spec.markStale(command.freshSnapshot(), List.of(), now);
            specRepository.save(spec);
            throw new SnapshotChangedException(spec.getId());
        }

        Broker broker = connection.map(ExchangeConnection::getBroker)
                .orElse(command.freshSnapshot().broker());

        // The specification's currency wins, and is not merely one of three opinions. It is what
        // the differences were computed with - it decided which snapshot line was filed as cash
        // (C10) - so a portfolio valued in anything else would key its cash position differently
        // from the specification that produced it, and the first deposit would land beside it.
        Currency currency = spec.getDenominationCurrency();
        if (!command.denominationCurrency().getId().equalsIgnoreCase(currency.getId())) {
            throw new ConnectionMismatchException("denominationCurrency",
                    command.denominationCurrency().getId(), currency.getId(), "specification");
        }

        List<Asset> assets = assetsOf(spec);
        Portfolio portfolio = portfolioFactory.withAssets(
                PortfolioId.generate(),
                command.portfolioName(),
                command.userId(),
                broker,
                currency,
                assets,
                List.of(openingContribution(assets, broker, currency, now)));

        Portfolio saved = portfolioRepository.save(portfolio);
        spec.markApplied(saved.getPortfolioId(), now);
        PortfolioSpec appliedSpec = specRepository.save(spec);

        confirmConnection(command, spec, saved.getPortfolioId());

        log.info("Specification [{}] applied as portfolio [{}] with {} position(s)",
                spec.getId().getId(), saved.getPortfolioId().getId(), portfolio.getAssets().size());
        return appliedSpec;
    }

    /**
     * What this account was worth on the day we first read it (task C12).
     *
     * <p>One entry, not a reconstructed history: the exchange told us what is held, not how it got
     * there. Its provenance says exactly that — {@code OPENING_SNAPSHOT}, never
     * {@code EXCHANGE_REPORTED} — so nobody later mistakes it for a deposit somebody made. Backfill
     * (C13) replaces it once real deposits can be read, which is why it carries an id.
     *
     * <p>Valued position by position rather than as one figure, so a single unpriced holding costs
     * one line instead of the whole number — the same reason the design chose a list over a scalar.
     */
    private Contribution openingContribution(
            List<Asset> assets, Broker broker, Currency currency, ZonedDateTime now) {

        Money opening = assets.stream()
                .map(asset -> quoteRestClient
                        .fetch(broker, Symbol.of(asset.getTicker(), Ticker.of(currency.getId())))
                        .getCurrentPrice()
                        .multiply(asset.getQuantity()))
                .reduce(Money.zero(currency.getId()), Money::plus);

        return Contribution.opening(opening.withScale(4), now);
    }

    /**
     * Positions the specification settled become the portfolio's opening state. A difference the
     * user answered "I do not know" yields a position with no cost — which is the honest record,
     * and the whole reason {@code CostBasis} is nullable.
     */
    private static List<Asset> assetsOf(PortfolioSpec spec) {
        return spec.getDifferences().stream()
                .filter(difference -> difference.direction() == DifferenceDirection.INCREASED)
                .map(ConfirmPortfolioSpecCommandHandler::toAsset)
                .toList();
    }

    private static Asset toAsset(Difference difference) {
        return Asset.builder()
                .ticker(difference.ticker())
                .subName(difference.subName())
                .costBasis(difference.resolvedCost())
                .quantity(difference.quantity())
                .locked(Quantity.zero(difference.quantity().getUnit()))
                .free(difference.quantity())
                .activeLocks(new HashSet<>())
                .build();
    }

    /**
     * Loads the connection and confronts what the request claims with what it says.
     *
     * <p>Empty when the specification has no connection — then the request is the only source,
     * and the fields it carries are used as given.
     */
    private Optional<ExchangeConnection> confirmableConnection(
            ConfirmPortfolioSpecCommand command, PortfolioSpec spec) {

        if (spec.getConnectionId() == null) {
            return Optional.empty();
        }
        ExchangeConnection connection = connectionRepository.findOwnedOrThrow(
                command.userId(), ExchangeConnectionId.of(spec.getConnectionId()));

        requireSame("denominationCurrency",
                command.denominationCurrency().getId(),
                connection.getDenominationCurrency().getId());
        requireSame("broker",
                command.freshSnapshot().broker().getId(),
                connection.getBroker().getId());

        // Asked here rather than left to the aggregate, which would raise it after the portfolio
        // had been written. A connection already serving a portfolio is the reachable case: nothing
        // stops a second specification from naming it, and without this the second confirmation
        // left an orphan portfolio behind before failing.
        if (connection.getStatus() != ConnectionStatus.PENDING) {
            throw new IllegalConnectionTransitionException(
                    connection.getId(), connection.getStatus(), "confirm");
        }

        return Optional.of(connection);
    }

    private static void requireSame(String field, String stated, String onConnection) {
        if (!stated.equalsIgnoreCase(onConnection)) {
            throw new ConnectionMismatchException(field, stated, onConnection);
        }
    }

    /**
     * The connection is a separate aggregate in a separate module, so it is closed through its
     * own command rather than by reaching into its repository.
     */
    private void confirmConnection(
            ConfirmPortfolioSpecCommand command, PortfolioSpec spec, PortfolioId portfolioId) {
        if (spec.getConnectionId() == null) {
            return;
        }
        confirmConnectionHandler.handle(new ConfirmExchangeConnectionCommand(
                command.userId(),
                ExchangeConnectionId.of(spec.getConnectionId()),
                portfolioId));
    }

    /**
     * Compares what the exchange reported then and now. Quantities are compared with a tolerance
     * because {@code Quantity} is backed by a double (F2).
     */
    private static boolean movedOn(ExchangeSnapshot before, ExchangeSnapshot now) {
        if (before.positions().size() != now.positions().size()) {
            return true;
        }
        return before.positions().stream().anyMatch(position -> now.find(position.ticker())
                .map(fresh -> differs(position.total(), fresh.total())
                        || differs(position.traded(), fresh.traded()))
                .orElse(true));
    }

    private static boolean differs(Quantity left, Quantity right) {
        return Math.abs(left.getQty() - right.getQty()) >= 1e-9;
    }
}
