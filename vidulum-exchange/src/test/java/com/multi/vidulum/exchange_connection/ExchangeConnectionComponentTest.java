package com.multi.vidulum.exchange_connection;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.exchange_connection.domain.ConnectionStatus;
import com.multi.vidulum.exchange_connection.domain.CredentialsMode;
import com.multi.vidulum.exchange_connection.domain.DomainExchangeConnectionRepository;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnection;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnectionId;
import com.multi.vidulum.exchange_connection.domain.ExchangeEnvironment;
import com.multi.vidulum.common.Broker;
import com.multi.vidulum.exchange_connection.domain.KeyPermissionsTooBroadException;
import com.multi.vidulum.exchange_connection.domain.ReportedKeyPermissions;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Component tests: the aggregate driven through {@link DomainExchangeConnectionRepository} with
 * {@link InMemoryExchangeConnectionRepository}, no Spring and no containers — the level
 * {@code PortfolioTest} uses in vidulum-wealth.
 *
 * <p>The division of labour against the other two suites is deliberate.
 * {@code ExchangeConnectionTest} asserts single transitions in memory;
 * {@code ExchangeConnectionPersistenceTest} proves what only a real server can — that the unique
 * index exists and that the derived queries resolve. These tests cover what neither does: whole
 * onboarding sequences where each step is written and read back, so a state that survives in a
 * field but is lost in the mapping shows up here.
 */
@Slf4j
class ExchangeConnectionComponentTest {

    private final Clock clock = Clock.fixed(Instant.parse("2022-01-01T00:00:00Z"), ZoneId.of("UTC"));
    private final InMemoryExchangeConnectionRepository repository =
            new InMemoryExchangeConnectionRepository();

    /** Declared here, not imported: this module must not depend on any exchange's module. */
    private static final Broker OKX = Broker.of("OKX");

    private static final UserId ALICE = UserId.of("U10000001");
    private static final UserId BOB = UserId.of("U10000002");
    private static final String ACCOUNT_UID = "349378528917283";
    private static final PortfolioId PORTFOLIO = PortfolioId.of("portfolio-1");

    private ZonedDateTime now() {
        return ZonedDateTime.now(clock);
    }

    private ZonedDateTime daysLater(long days) {
        return now().plusDays(days);
    }

    private ExchangeConnection describe(
            String id, UserId user, ExchangeEnvironment environment, String accountUid) {
        return ExchangeConnection.pending(
                ExchangeConnectionId.of(id),
                user,
                OKX,
                accountUid,
                environment,
                "EEA",
                ReportedKeyPermissions.of("read_only"),
                CredentialsMode.EXTERNAL,
                Currency.of("EUR"),
                now());
    }

    /**
     * Onboarding as the POC will drive it: describe the account, look it up by natural key, then
     * confirm it once the portfolio exists.
     */
    @Test
    void shouldOnboardAccountThatWasNeverConnectedBefore() {
        assertThat(repository.findByAccount(ALICE, OKX, ExchangeEnvironment.DEMO, ACCOUNT_UID))
                .isEmpty();

        repository.save(describe("conn-1", ALICE, ExchangeEnvironment.DEMO, ACCOUNT_UID));

        ExchangeConnection pending = repository
                .findByAccount(ALICE, OKX, ExchangeEnvironment.DEMO, ACCOUNT_UID)
                .orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(ConnectionStatus.PENDING);
        assertThat(pending.isOnboarded()).isFalse();

        pending.confirm(PORTFOLIO, daysLater(1));
        repository.save(pending);

        ExchangeConnection active = repository.findById(ExchangeConnectionId.of("conn-1")).orElseThrow();
        assertThat(active.getStatus()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(active.getPortfolioId()).isEqualTo(PORTFOLIO);
        assertThat(active.getLastSyncAt()).isEqualTo(daysLater(1));
        assertThat(repository.size()).isEqualTo(1);
    }

    /**
     * The sequence the whole entity exists for: a break long enough that the user comes back, and
     * the portfolio is still there on the other side. Every step is written and read back, so a
     * field that survives in memory but is lost on the way to storage fails here.
     */
    @Test
    void shouldCarryPortfolioThroughRevokeAndReconnect() {
        ExchangeConnection connection = describe("conn-1", ALICE, ExchangeEnvironment.LIVE, ACCOUNT_UID);
        connection.confirm(PORTFOLIO, daysLater(1));
        repository.save(connection);

        ExchangeConnection active = repository.findById(ExchangeConnectionId.of("conn-1")).orElseThrow();
        active.revoke("api key expired", daysLater(30));
        repository.save(active);

        ExchangeConnection revoked = repository
                .findByAccount(ALICE, OKX, ExchangeEnvironment.LIVE, ACCOUNT_UID)
                .orElseThrow();
        assertThat(revoked.getStatus()).isEqualTo(ConnectionStatus.REVOKED);
        assertThat(revoked.getStatusReason()).isEqualTo("api key expired");
        assertThat(revoked.isOnboarded()).as("portfolio survives the break").isTrue();

        revoked.reconnect(daysLater(120));
        repository.save(revoked);

        ExchangeConnection reconnected = repository.findById(ExchangeConnectionId.of("conn-1")).orElseThrow();
        assertThat(reconnected.getStatus()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(reconnected.getStatusReason()).isNull();
        assertThat(reconnected.getPortfolioId()).isEqualTo(PORTFOLIO);
        assertThat(reconnected.getLastSyncAt()).isEqualTo(daysLater(120));
        assertThat(repository.size()).as("reconnect must not create a second record").isEqualTo(1);
    }

    /**
     * Returning after a break is a lookup, not a new connection. If this ever produced a second
     * record, the synchronisation engine would compute its difference against an empty known
     * state and the user would be asked about their whole portfolio again.
     */
    @Test
    void shouldRefuseSecondConnectionOfAnAccountThatIsAlreadyKnown() {
        ExchangeConnection first = describe("conn-1", ALICE, ExchangeEnvironment.DEMO, ACCOUNT_UID);
        first.confirm(PORTFOLIO, daysLater(1));
        repository.save(first);
        first.revoke("user disconnected", daysLater(10));
        repository.save(first);

        ExchangeConnection secondAttempt = describe("conn-2", ALICE, ExchangeEnvironment.DEMO, ACCOUNT_UID);

        assertThatThrownBy(() -> repository.save(secondAttempt))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("conn-1");

        assertThat(repository.size()).isEqualTo(1);
    }

    /**
     * Vidulum never places orders and never moves funds, so a key that could do either is refused
     * outright rather than merely left unused. The refusal happens before anything is written —
     * there is no half-onboarded record left behind to clean up.
     */
    @Test
    void shouldNeverStoreConnectionWhoseKeyCouldTrade() {
        assertThatThrownBy(() -> ExchangeConnection.pending(
                ExchangeConnectionId.of("conn-1"),
                ALICE,
                OKX,
                ACCOUNT_UID,
                ExchangeEnvironment.LIVE,
                "EEA",
                ReportedKeyPermissions.of("read_only,withdraw"),
                CredentialsMode.EXTERNAL,
                Currency.of("EUR"),
                now()))
                .isInstanceOf(KeyPermissionsTooBroadException.class);

        assertThat(repository.size()).isZero();
        assertThat(repository.findByAccount(ALICE, OKX, ExchangeEnvironment.LIVE, ACCOUNT_UID))
                .isEmpty();
    }

    @Test
    void shouldKeepDemoAndLiveConnectionsOfTheSameAccountApart() {
        repository.save(describe("conn-demo", ALICE, ExchangeEnvironment.DEMO, ACCOUNT_UID));

        ExchangeConnection live = describe("conn-live", ALICE, ExchangeEnvironment.LIVE, ACCOUNT_UID);
        live.confirm(PORTFOLIO, daysLater(1));
        repository.save(live);

        assertThat(repository.findByAccount(ALICE, OKX, ExchangeEnvironment.DEMO, ACCOUNT_UID))
                .get()
                .extracting(ExchangeConnection::isOnboarded)
                .isEqualTo(false);
        assertThat(repository.findByAccount(ALICE, OKX, ExchangeEnvironment.LIVE, ACCOUNT_UID))
                .get()
                .extracting(ExchangeConnection::getPortfolioId)
                .isEqualTo(PORTFOLIO);
        assertThat(repository.findByUserId(ALICE)).hasSize(2);
    }

    /**
     * A failed attempt does not strand the connection: the reason is stored, and a retry takes it
     * back to the start of onboarding. (A key that is too broad never gets this far — it is
     * refused before the connection exists.)
     */
    @Test
    void shouldRecoverFromFailedAttemptAndOnboardAfterwards() {
        ExchangeConnection connection = describe("conn-1", ALICE, ExchangeEnvironment.DEMO, ACCOUNT_UID);
        connection.fail("exchange answered 401", daysLater(1));
        repository.save(connection);

        ExchangeConnection failed = repository.findById(ExchangeConnectionId.of("conn-1")).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(ConnectionStatus.ERROR);
        assertThat(failed.getStatusReason()).isEqualTo("exchange answered 401");

        failed.retry();
        failed.confirm(PORTFOLIO, daysLater(2));
        repository.save(failed);

        ExchangeConnection recovered = repository.findById(ExchangeConnectionId.of("conn-1")).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(recovered.getStatusReason()).isNull();
        assertThat(recovered.getPortfolioId()).isEqualTo(PORTFOLIO);
    }

    /**
     * Reading the account and applying what was read drift apart, and the interface has to show
     * them separately. Both have to survive storage for that to be possible.
     */
    @Test
    void shouldKeepSnapshotAndSyncTimestampsApartAcrossSaves() {
        ExchangeConnection connection = describe("conn-1", ALICE, ExchangeEnvironment.DEMO, ACCOUNT_UID);
        connection.confirm(PORTFOLIO, daysLater(1));
        repository.save(connection);

        ExchangeConnection active = repository.findById(ExchangeConnectionId.of("conn-1")).orElseThrow();
        active.recordSnapshot(daysLater(5));
        repository.save(active);

        ExchangeConnection afterSnapshot = repository.findById(ExchangeConnectionId.of("conn-1")).orElseThrow();
        assertThat(afterSnapshot.getLastSnapshotAt()).isEqualTo(daysLater(5));
        assertThat(afterSnapshot.getLastSyncAt()).isEqualTo(daysLater(1));

        afterSnapshot.recordSync(daysLater(5));
        repository.save(afterSnapshot);

        // Compared as instants on purpose: the mapping normalises every timestamp to the UTC
        // offset, so a value written as ZoneId "UTC" comes back as offset "Z". Same moment,
        // different zone object — and plain equals on ZonedDateTime tells them apart.
        assertThat(repository.findById(ExchangeConnectionId.of("conn-1")).orElseThrow())
                .extracting(
                        reloaded -> reloaded.getLastSnapshotAt().toInstant(),
                        reloaded -> reloaded.getLastSyncAt().toInstant())
                .containsExactly(daysLater(5).toInstant(), daysLater(5).toInstant());
    }

    /**
     * The natural key is scoped to the user, so one person connecting an account cannot lock
     * anyone else out of connecting the same one.
     */
    @Test
    void shouldLetTwoUsersConnectTheSameExchangeAccountIndependently() {
        ExchangeConnection alice = describe("conn-alice", ALICE, ExchangeEnvironment.DEMO, ACCOUNT_UID);
        alice.confirm(PortfolioId.of("portfolio-alice"), daysLater(1));
        repository.save(alice);

        ExchangeConnection bob = describe("conn-bob", BOB, ExchangeEnvironment.DEMO, ACCOUNT_UID);
        bob.confirm(PortfolioId.of("portfolio-bob"), daysLater(1));
        repository.save(bob);

        assertThat(repository.findByPortfolioId(PortfolioId.of("portfolio-alice")))
                .get()
                .extracting(ExchangeConnection::getUserId)
                .isEqualTo(ALICE);
        assertThat(repository.findByPortfolioId(PortfolioId.of("portfolio-bob")))
                .get()
                .extracting(ExchangeConnection::getUserId)
                .isEqualTo(BOB);
    }
}
