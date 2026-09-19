package com.multi.vidulum.exchange_connection;

import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.auth.AuthenticatedUserProvider;
import com.multi.vidulum.exchange_connection.app.ConnectExchangeRequest;
import com.multi.vidulum.exchange_connection.app.ExchangeConnectionJson;
import com.multi.vidulum.exchange_connection.app.ExchangeConnectionRestController;
import com.multi.vidulum.exchange_connection.app.ExchangeConnectionService;
import com.multi.vidulum.exchange_connection.app.ExchangeConnectionsListJson;
import com.multi.vidulum.exchange_connection.domain.CredentialsMode;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnection;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnectionId;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnectionNotFoundException;
import com.multi.vidulum.exchange_connection.domain.ExchangeEnvironment;
import com.multi.vidulum.exchange_connection.domain.ExchangeNotSupportedException;
import com.multi.vidulum.exchange_connection.domain.IllegalConnectionTransitionException;
import com.multi.vidulum.exchange_connection.domain.KeyPermissionsTooBroadException;
import com.multi.vidulum.exchange_connection.domain.UnknownExchangeRegionException;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Component test of the controller: the whole path from the request object down to the stored
 * document and back into the response, with no Spring context and no containers.
 *
 * <p>Follows {@code CashFlowControllerTest}, which drives the controller's own methods rather
 * than HTTP and asserts whole objects — but without the {@code @SpringBootTest} that test needs,
 * because everything here has an in-memory collaborator. It runs in milliseconds, which is why
 * the exhaustive payload assertions live at this level while
 * {@code ExchangeConnectionEndpointTest} stays focused on what only HTTP can show: status codes
 * and serialisation.
 *
 * <p>Responses are compared with {@code usingRecursiveComparison} against objects built with the
 * all-args constructor, as {@code CLAUDE.md} requires: adding a field to
 * {@link ExchangeConnectionJson} then fails to compile here instead of silently going untested.
 */
@Slf4j
class ExchangeConnectionControllerComponentTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2022-01-01T00:00:00Z");
    private static final ZonedDateTime LATER = ZonedDateTime.parse("2022-03-04T10:15:30Z");
    private static final UserId ALICE = UserId.of("U10000001");
    private static final UserId BOB = UserId.of("U10000002");
    private static final String ACCOUNT_UID = "349378528917283";

    private final Clock clock = Clock.fixed(Instant.parse("2022-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final InMemoryExchangeConnectionRepository repository =
            new InMemoryExchangeConnectionRepository();
    private final ExchangeConnectionService service =
            new ExchangeConnectionService(repository, clock, List.of(new StubExchangeAdapter()));

    /** Switchable stand-in for the JWT principal. */
    private UserId caller = ALICE;
    private final AuthenticatedUserProvider authenticatedUserProvider = () -> caller;

    private final ExchangeConnectionRestController controller =
            new ExchangeConnectionRestController(service, authenticatedUserProvider);

    private static ConnectExchangeRequest request(String broker, String region, String permissions) {
        return new ConnectExchangeRequest(
                broker, ACCOUNT_UID, ExchangeEnvironment.DEMO, region, permissions, "EUR", null);
    }

    private static ConnectExchangeRequest validRequest() {
        return request("DEMOEX", "EU", "read_only");
    }

    /**
     * Every field of the response asserted at once, including the ones that must be absent on a
     * fresh connection.
     */
    @Test
    void shouldReturnTheWholeConnectionAfterRegistering() {
        ExchangeConnectionJson created = controller.connect(validRequest());

        assertThat(created.id()).isNotBlank();
        assertThat(created)
                .usingRecursiveComparison()
                .isEqualTo(new ExchangeConnectionJson(
                        created.id(),
                        ALICE.getId(),
                        "DEMOEX",
                        ACCOUNT_UID,
                        "DEMO",
                        "EU",
                        "read_only",
                        "EXTERNAL",
                        "EUR",
                        null,
                        "PENDING",
                        null,
                        null,
                        null,
                        NOW));
    }

    /**
     * {@code credentialsMode} is optional on the request and defaults to the POC's mode — the
     * backend holds no credentials at all.
     */
    @Test
    void shouldDefaultCredentialsModeToExternalWhenOmitted() {
        assertThat(controller.connect(validRequest()).credentialsMode()).isEqualTo("EXTERNAL");

        ConnectExchangeRequest explicit = new ConnectExchangeRequest(
                "DEMOEX", "999", ExchangeEnvironment.LIVE, "US", "read_only", "EUR",
                CredentialsMode.STORED_ENCRYPTED);

        assertThat(controller.connect(explicit).credentialsMode()).isEqualTo("STORED_ENCRYPTED");
    }

    @Test
    void shouldNormaliseBrokerNameSoCasingCannotSplitTheNaturalKey() {
        assertThat(controller.connect(request("demoex", "EU", "read_only")).broker())
                .isEqualTo("DEMOEX");
    }

    @Test
    void shouldReadBackTheConnectionItJustCreated() {
        ExchangeConnectionJson created = controller.connect(validRequest());

        assertThat(controller.get(created.id()))
                .usingRecursiveComparison()
                .isEqualTo(created);
    }

    /**
     * The state after a break, asserted as a whole: active again, reason cleared, portfolio and
     * both timestamps intact.
     */
    @Test
    void shouldReturnTheWholeConnectionAfterReconnecting() {
        ExchangeConnectionJson created = controller.connect(validRequest());

        ExchangeConnection stored = repository.findById(ExchangeConnectionId.of(created.id())).orElseThrow();
        stored.confirm(PortfolioId.of("portfolio-1"), NOW);
        stored.recordSnapshot(LATER);
        stored.revoke("api key expired", LATER);
        repository.save(stored);

        ExchangeConnectionJson reconnected = controller.reconnect(created.id());

        assertThat(reconnected)
                .usingRecursiveComparison()
                .isEqualTo(new ExchangeConnectionJson(
                        created.id(),
                        ALICE.getId(),
                        "DEMOEX",
                        ACCOUNT_UID,
                        "DEMO",
                        "EU",
                        "read_only",
                        "EXTERNAL",
                        "EUR",
                        "portfolio-1",
                        "ACTIVE",
                        null,
                        LATER,
                        NOW,
                        NOW));
    }

    /**
     * A revoked connection carries its reason to the caller; without it the status is opaque.
     */
    @Test
    void shouldExposeStatusAndReasonOfARevokedConnection() {
        ExchangeConnectionJson created = controller.connect(validRequest());

        ExchangeConnection stored = repository.findById(ExchangeConnectionId.of(created.id())).orElseThrow();
        stored.confirm(PortfolioId.of("portfolio-1"), NOW);
        stored.revoke("api key expired", LATER);
        repository.save(stored);

        ExchangeConnectionJson read = controller.get(created.id());

        assertThat(read.status()).isEqualTo("REVOKED");
        assertThat(read.statusReason()).isEqualTo("api key expired");
        assertThat(read.portfolioId())
                .as("a revoked connection keeps its portfolio")
                .isEqualTo("portfolio-1");
    }

    /**
     * The two clocks reach the response as separate fields. Collapsing them would hide which one
     * is stale — balances read three days ago priced five seconds ago look fresh.
     */
    @Test
    void shouldReturnBothStalenessTimestampsSeparately() {
        ExchangeConnectionJson created = controller.connect(validRequest());

        ExchangeConnection stored = repository.findById(ExchangeConnectionId.of(created.id())).orElseThrow();
        stored.confirm(PortfolioId.of("portfolio-1"), NOW);
        stored.recordSnapshot(LATER);
        repository.save(stored);

        ExchangeConnectionJson read = controller.get(created.id());

        assertThat(read.lastSnapshotAt()).isEqualTo(LATER);
        assertThat(read.lastSyncAt()).isEqualTo(NOW);
        assertThat(read.lastSnapshotAt()).isNotEqualTo(read.lastSyncAt());
    }

    @Test
    void shouldListTheCallersConnectionsWithTheSupportedExchanges() {
        ExchangeConnectionJson alices = controller.connect(validRequest());

        caller = BOB;
        controller.connect(validRequest());

        caller = ALICE;
        ExchangeConnectionsListJson listed = controller.list();

        assertThat(listed)
                .usingRecursiveComparison()
                .isEqualTo(new ExchangeConnectionsListJson(List.of(alices), List.of("DEMOEX")));
    }

    @Test
    void shouldListNothingForACallerWithNoConnections() {
        assertThat(controller.list())
                .usingRecursiveComparison()
                .isEqualTo(new ExchangeConnectionsListJson(List.of(), List.of("DEMOEX")));
    }

    /**
     * The caller is taken from the authenticated principal, never from the request.
     */
    @Test
    void shouldAttributeTheConnectionToTheAuthenticatedCaller() {
        caller = BOB;

        assertThat(controller.connect(validRequest()).userId()).isEqualTo(BOB.getId());
    }

    @Test
    void shouldHideAnotherCallersConnection() {
        ExchangeConnectionJson alices = controller.connect(validRequest());

        caller = BOB;

        assertThatThrownBy(() -> controller.get(alices.id()))
                .isInstanceOf(ExchangeConnectionNotFoundException.class);
        assertThatThrownBy(() -> controller.reconnect(alices.id()))
                .isInstanceOf(ExchangeConnectionNotFoundException.class);
        assertThat(controller.list().connections()).isEmpty();
    }

    @Test
    void shouldRefuseUnsupportedExchange() {
        assertThatThrownBy(() -> controller.connect(request("BINANCE", "EU", "read_only")))
                .isInstanceOf(ExchangeNotSupportedException.class);
        assertThat(repository.size()).isZero();
    }

    @Test
    void shouldRefuseRegionTheExchangeDoesNotServe() {
        assertThatThrownBy(() -> controller.connect(request("DEMOEX", "MARS", "read_only")))
                .isInstanceOf(UnknownExchangeRegionException.class);
        assertThat(repository.size()).isZero();
    }

    @Test
    void shouldRefuseKeyThatCouldTrade() {
        assertThatThrownBy(() -> controller.connect(request("DEMOEX", "EU", "read_only,trade")))
                .isInstanceOf(KeyPermissionsTooBroadException.class);
        assertThat(repository.size()).isZero();
    }

    @Test
    void shouldRefuseToReconnectAConnectionThatIsNotRevoked() {
        ExchangeConnectionJson created = controller.connect(validRequest());

        assertThatThrownBy(() -> controller.reconnect(created.id()))
                .isInstanceOf(IllegalConnectionTransitionException.class);
    }

    @Test
    void shouldReportUnknownConnectionAsNotFound() {
        assertThatThrownBy(() -> controller.get("no-such-connection"))
                .isInstanceOf(ExchangeConnectionNotFoundException.class);
    }
}
