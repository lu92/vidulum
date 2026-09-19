package com.multi.vidulum.exchange_connection;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.exchange_connection.app.ConnectExchangeCommand;
import com.multi.vidulum.exchange_connection.app.ExchangeConnectionService;
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
 * Component tests for the onboarding service: real aggregate, real mapping through
 * {@link InMemoryExchangeConnectionRepository}, a stub adapter standing in for an exchange's
 * module. No Spring, no containers.
 */
class ExchangeConnectionServiceTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2022-01-01T00:00:00Z");
    private static final UserId ALICE = UserId.of("U10000001");
    private static final UserId BOB = UserId.of("U10000002");
    private static final String ACCOUNT_UID = "349378528917283";

    private final Clock clock = Clock.fixed(Instant.parse("2022-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final InMemoryExchangeConnectionRepository repository =
            new InMemoryExchangeConnectionRepository();
    private final ExchangeConnectionService service = new ExchangeConnectionService(
            repository, clock, List.of(new StubExchangeAdapter()));

    private static ConnectExchangeCommand command(String broker, String region, String permissions) {
        return new ConnectExchangeCommand(
                broker, ACCOUNT_UID, ExchangeEnvironment.DEMO, region, permissions, "EUR",
                CredentialsMode.EXTERNAL);
    }

    private static ConnectExchangeCommand validCommand() {
        return command("DEMOEX", "EU", "read_only");
    }

    @Test
    void shouldConnectAccountAsPendingWithoutPortfolio() {
        ExchangeConnection connection = service.connect(ALICE, validCommand());

        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(connection.getPortfolioId()).isNull();
        assertThat(connection.getUserId()).isEqualTo(ALICE);
        assertThat(connection.getBroker()).isEqualTo(StubExchangeAdapter.DEMOEX);
        assertThat(connection.getCreatedAt()).isEqualTo(NOW);
        assertThat(repository.size()).isEqualTo(1);
    }

    @Test
    void shouldAcceptBrokerNameInAnyCaseAndStoreItNormalised() {
        ExchangeConnection connection = service.connect(ALICE, command("demoex", "EU", "read_only"));

        assertThat(connection.getBroker()).isEqualTo(Broker.of("DEMOEX"));
        assertThat(service.get(ALICE, connection.getId()).getBroker()).isEqualTo(Broker.of("DEMOEX"));
    }

    @Test
    void shouldRefuseBrokerWithNoRegisteredAdapter() {
        assertThatThrownBy(() -> service.connect(ALICE, command("BINANCE", "EU", "read_only")))
                .isInstanceOf(ExchangeNotSupportedException.class)
                .hasMessageContaining("BINANCE")
                .hasMessageContaining("DEMOEX");

        assertThat(repository.size()).isZero();
    }

    @Test
    void shouldRefuseRegionTheExchangeDoesNotServe() {
        assertThatThrownBy(() -> service.connect(ALICE, command("DEMOEX", "MARS", "read_only")))
                .isInstanceOf(UnknownExchangeRegionException.class)
                .hasMessageContaining("MARS")
                .hasMessageContaining("EU");

        assertThat(repository.size()).isZero();
    }

    @Test
    void shouldRefuseKeyThatCouldTradeBeforeStoringAnything() {
        assertThatThrownBy(() -> service.connect(ALICE, command("DEMOEX", "EU", "read_only,trade")))
                .isInstanceOf(KeyPermissionsTooBroadException.class);

        assertThat(repository.size()).isZero();
    }

    /**
     * The refusal that stops one account turning into two portfolios over the same assets.
     */
    @Test
    void shouldRefuseToConnectAnAccountThatIsAlreadyKnown() {
        service.connect(ALICE, validCommand());

        assertThatThrownBy(() -> service.connect(ALICE, validCommand()))
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
        ExchangeConnection connection = service.connect(ALICE, validCommand());
        connection.confirm(PortfolioId.of("portfolio-1"), NOW);
        connection.revoke("api key expired", NOW);
        repository.save(connection);

        assertThatThrownBy(() -> service.connect(ALICE, validCommand()))
                .isInstanceOf(ExchangeAccountAlreadyConnectedException.class);
    }

    @Test
    void shouldLetAnotherUserConnectTheSameExchangeAccount() {
        service.connect(ALICE, validCommand());

        ExchangeConnection bobs = service.connect(BOB, validCommand());

        assertThat(bobs.getUserId()).isEqualTo(BOB);
        assertThat(repository.size()).isEqualTo(2);
    }

    @Test
    void shouldReconnectRevokedConnectionKeepingItsPortfolio() {
        ExchangeConnection connection = service.connect(ALICE, validCommand());
        connection.confirm(PortfolioId.of("portfolio-1"), NOW);
        connection.revoke("api key expired", NOW);
        repository.save(connection);

        ExchangeConnection reconnected = service.reconnect(ALICE, connection.getId());

        assertThat(reconnected.getStatus()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(reconnected.getStatusReason()).isNull();
        assertThat(reconnected.getPortfolioId()).isEqualTo(PortfolioId.of("portfolio-1"));
        assertThat(repository.size()).isEqualTo(1);
    }

    @Test
    void shouldRefuseToReconnectConnectionThatIsNotRevoked() {
        ExchangeConnection connection = service.connect(ALICE, validCommand());

        assertThatThrownBy(() -> service.reconnect(ALICE, connection.getId()))
                .isInstanceOf(IllegalConnectionTransitionException.class)
                .hasMessageContaining("PENDING");
    }

    /**
     * Someone else's connection must answer {@code not found}, never {@code forbidden} — a 403
     * would confirm the id exists.
     */
    @Test
    void shouldHideAnotherUsersConnection() {
        ExchangeConnection alices = service.connect(ALICE, validCommand());

        assertThatThrownBy(() -> service.get(BOB, alices.getId()))
                .isInstanceOf(ExchangeConnectionNotFoundException.class);
        assertThatThrownBy(() -> service.reconnect(BOB, alices.getId()))
                .isInstanceOf(ExchangeConnectionNotFoundException.class);
    }

    @Test
    void shouldReportUnknownConnectionAsNotFound() {
        assertThatThrownBy(() -> service.get(ALICE, ExchangeConnectionId.of("nope")))
                .isInstanceOf(ExchangeConnectionNotFoundException.class);
    }

    @Test
    void shouldListOnlyTheCallersConnections() {
        service.connect(ALICE, validCommand());
        service.connect(BOB, validCommand());

        assertThat(service.list(ALICE)).hasSize(1);
        assertThat(service.list(ALICE).getFirst().getUserId()).isEqualTo(ALICE);
    }

    @Test
    void shouldReportWhichExchangesAreSupported() {
        assertThat(service.supportedExchanges()).containsExactly("DEMOEX");
    }
}
