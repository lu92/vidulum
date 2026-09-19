package com.multi.vidulum.exchange_connection;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.exchange_connection.app.commands.connect.ConnectExchangeCommand;
import com.multi.vidulum.exchange_connection.app.commands.connect.ConnectExchangeCommandHandler;
import com.multi.vidulum.exchange_connection.app.commands.reconnect.ReconnectExchangeCommand;
import com.multi.vidulum.exchange_connection.app.commands.reconnect.ReconnectExchangeCommandHandler;
import com.multi.vidulum.exchange_connection.app.queries.GetExchangeConnectionQuery;
import com.multi.vidulum.exchange_connection.app.queries.GetExchangeConnectionQueryHandler;
import com.multi.vidulum.exchange_connection.app.queries.GetExchangeConnectionsOfUserQuery;
import com.multi.vidulum.exchange_connection.app.queries.GetExchangeConnectionsOfUserQueryHandler;
import com.multi.vidulum.exchange_connection.domain.ExchangeAdapters;
import com.multi.vidulum.exchange_connection.domain.ConnectionStatus;
import com.multi.vidulum.exchange_connection.domain.CredentialsMode;
import com.multi.vidulum.exchange_connection.domain.ExchangeAccountAlreadyConnectedException;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnection;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnectionId;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnectionNotFoundException;
import com.multi.vidulum.exchange_connection.domain.ExchangeEnvironment;
import com.multi.vidulum.exchange_connection.domain.ExchangeNotSupportedException;
import com.multi.vidulum.exchange_connection.domain.IllegalConnectionTransitionException;
import com.multi.vidulum.exchange_connection.domain.KeyPermissionsTooBroadException;
import com.multi.vidulum.exchange_connection.domain.UnknownExchangeRegionException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Component tests for the command and query handlers: real aggregate, real mapping through
 * {@link InMemoryExchangeConnectionRepository}, a stub adapter standing in for an exchange's
 * module. No Spring, no containers — the handlers are plain objects, so they need none.
 */
class ExchangeConnectionHandlersTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2022-01-01T00:00:00Z");
    private static final UserId ALICE = UserId.of("U10000001");
    private static final UserId BOB = UserId.of("U10000002");
    private static final String ACCOUNT_UID = "349378528917283";

    private final Clock clock = Clock.fixed(Instant.parse("2022-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final InMemoryExchangeConnectionRepository repository =
            new InMemoryExchangeConnectionRepository();
    private final ExchangeAdapters adapters = new ExchangeAdapters(List.of(new StubExchangeAdapter()));

    private final ConnectExchangeCommandHandler connectHandler =
            new ConnectExchangeCommandHandler(repository, adapters, clock);
    private final ReconnectExchangeCommandHandler reconnectHandler =
            new ReconnectExchangeCommandHandler(repository, clock);
    private final GetExchangeConnectionQueryHandler getHandler =
            new GetExchangeConnectionQueryHandler(repository);
    private final GetExchangeConnectionsOfUserQueryHandler listHandler =
            new GetExchangeConnectionsOfUserQueryHandler(repository, adapters);

    private ExchangeConnection connect(UserId userId, ConnectExchangeCommand command) {
        return connectHandler.handle(command.userId().equals(userId) ? command
                : new ConnectExchangeCommand(userId, command.broker(), command.accountUid(),
                command.environment(), command.region(), command.reportedKeyPermissions(),
                command.denominationCurrency(), command.credentialsMode()));
    }

    private static ConnectExchangeCommand command(String broker, String region, String permissions) {
        return new ConnectExchangeCommand(
                ALICE, Broker.of(broker), ACCOUNT_UID, ExchangeEnvironment.DEMO, region,
                permissions, Currency.of("EUR"), CredentialsMode.EXTERNAL);
    }

    private static ConnectExchangeCommand validCommand() {
        return command("DEMOEX", "EU", "read_only");
    }

    @Test
    void shouldConnectAccountAsPendingWithoutPortfolio() {
        ExchangeConnection connection = connect(ALICE, validCommand());

        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(connection.getPortfolioId()).isNull();
        assertThat(connection.getUserId()).isEqualTo(ALICE);
        assertThat(connection.getBroker()).isEqualTo(StubExchangeAdapter.DEMOEX);
        assertThat(connection.getCreatedAt()).isEqualTo(NOW);
        assertThat(repository.size()).isEqualTo(1);
    }

    @Test
    void shouldAcceptBrokerNameInAnyCaseAndStoreItNormalised() {
        ExchangeConnection connection = connect(ALICE, command("demoex", "EU", "read_only"));

        assertThat(connection.getBroker()).isEqualTo(Broker.of("DEMOEX"));
        assertThat(getHandler.query(new GetExchangeConnectionQuery(ALICE, connection.getId())).getBroker()).isEqualTo(Broker.of("DEMOEX"));
    }

    @Test
    void shouldRefuseBrokerWithNoRegisteredAdapter() {
        assertThatThrownBy(() -> connect(ALICE, command("BINANCE", "EU", "read_only")))
                .isInstanceOf(ExchangeNotSupportedException.class)
                .hasMessageContaining("BINANCE")
                .hasMessageContaining("DEMOEX");

        assertThat(repository.size()).isZero();
    }

    @Test
    void shouldRefuseRegionTheExchangeDoesNotServe() {
        assertThatThrownBy(() -> connect(ALICE, command("DEMOEX", "MARS", "read_only")))
                .isInstanceOf(UnknownExchangeRegionException.class)
                .hasMessageContaining("MARS")
                .hasMessageContaining("EU");

        assertThat(repository.size()).isZero();
    }

    @Test
    void shouldRefuseKeyThatCouldTradeBeforeStoringAnything() {
        assertThatThrownBy(() -> connect(ALICE, command("DEMOEX", "EU", "read_only,trade")))
                .isInstanceOf(KeyPermissionsTooBroadException.class);

        assertThat(repository.size()).isZero();
    }

    /**
     * The refusal that stops one account turning into two portfolios over the same assets.
     */
    @Test
    void shouldRefuseToConnectAnAccountThatIsAlreadyKnown() {
        connect(ALICE, validCommand());

        assertThatThrownBy(() -> connect(ALICE, validCommand()))
                .isInstanceOf(ExchangeAccountAlreadyConnectedException.class)
                .hasMessageContaining(ACCOUNT_UID);

        assertThat(repository.size()).isEqualTo(1);
    }

    /**
     * A revoked connection is still "known" — coming back is {@code reconnect}, not a second
     * registration, because the portfolio has to be kept.
     */
    @Test
    void shouldRefuseToConnectAgainEvenWhenTheExistingConnectionIsRevoked() {
        ExchangeConnection connection = connect(ALICE, validCommand());
        connection.confirm(PortfolioId.of("portfolio-1"), NOW);
        connection.revoke("api key expired", NOW);
        repository.save(connection);

        assertThatThrownBy(() -> connect(ALICE, validCommand()))
                .isInstanceOf(ExchangeAccountAlreadyConnectedException.class);
    }

    @Test
    void shouldLetAnotherUserConnectTheSameExchangeAccount() {
        connect(ALICE, validCommand());

        ExchangeConnection bobs = connect(BOB, validCommand());

        assertThat(bobs.getUserId()).isEqualTo(BOB);
        assertThat(repository.size()).isEqualTo(2);
    }

    @Test
    void shouldReconnectRevokedConnectionKeepingItsPortfolio() {
        ExchangeConnection connection = connect(ALICE, validCommand());
        connection.confirm(PortfolioId.of("portfolio-1"), NOW);
        connection.revoke("api key expired", NOW);
        repository.save(connection);

        ExchangeConnection reconnected = reconnectHandler.handle(new ReconnectExchangeCommand(ALICE, connection.getId()));

        assertThat(reconnected.getStatus()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(reconnected.getStatusReason()).isNull();
        assertThat(reconnected.getPortfolioId()).isEqualTo(PortfolioId.of("portfolio-1"));
        assertThat(repository.size()).isEqualTo(1);
    }

    @Test
    void shouldRefuseToReconnectConnectionThatIsNotRevoked() {
        ExchangeConnection connection = connect(ALICE, validCommand());

        assertThatThrownBy(() -> reconnectHandler.handle(new ReconnectExchangeCommand(ALICE, connection.getId())))
                .isInstanceOf(IllegalConnectionTransitionException.class)
                .hasMessageContaining("PENDING");
    }

    /**
     * Someone else's connection must answer {@code not found}, never {@code forbidden} — a 403
     * would confirm the id exists.
     */
    @Test
    void shouldHideAnotherUsersConnection() {
        ExchangeConnection alices = connect(ALICE, validCommand());

        assertThatThrownBy(() -> getHandler.query(new GetExchangeConnectionQuery(BOB, alices.getId())))
                .isInstanceOf(ExchangeConnectionNotFoundException.class);
        assertThatThrownBy(() -> reconnectHandler.handle(new ReconnectExchangeCommand(BOB, alices.getId())))
                .isInstanceOf(ExchangeConnectionNotFoundException.class);
    }

    @Test
    void shouldReportUnknownConnectionAsNotFound() {
        assertThatThrownBy(() -> getHandler.query(new GetExchangeConnectionQuery(ALICE, ExchangeConnectionId.of("nope"))))
                .isInstanceOf(ExchangeConnectionNotFoundException.class);
    }

    @Test
    void shouldListOnlyTheCallersConnections() {
        connect(ALICE, validCommand());
        connect(BOB, validCommand());

        assertThat(listHandler.query(new GetExchangeConnectionsOfUserQuery(ALICE)).connections()).hasSize(1);
        assertThat(listHandler.query(new GetExchangeConnectionsOfUserQuery(ALICE)).connections().getFirst().getUserId()).isEqualTo(ALICE);
    }

    @Test
    void shouldReportWhichExchangesAreSupported() {
        assertThat(listHandler.query(new GetExchangeConnectionsOfUserQuery(ALICE)).supportedExchanges()).containsExactly("DEMOEX");
    }
}
