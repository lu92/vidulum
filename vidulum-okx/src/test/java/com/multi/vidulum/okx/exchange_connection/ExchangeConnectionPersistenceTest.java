package com.multi.vidulum.okx.exchange_connection;

import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.okx.exchange_connection.domain.ConnectionStatus;
import com.multi.vidulum.okx.exchange_connection.domain.CredentialsMode;
import com.multi.vidulum.okx.exchange_connection.domain.DomainExchangeConnectionRepository;
import com.multi.vidulum.okx.exchange_connection.domain.Exchange;
import com.multi.vidulum.okx.exchange_connection.domain.ExchangeConnection;
import com.multi.vidulum.okx.exchange_connection.domain.ExchangeConnectionId;
import com.multi.vidulum.okx.exchange_connection.domain.ExchangeEnvironment;
import com.multi.vidulum.okx.exchange_connection.domain.ExchangeRegion;
import com.multi.vidulum.okx.exchange_connection.domain.KeyPermissionsTooBroadException;
import com.multi.vidulum.okx.exchange_connection.domain.ReportedKeyPermissions;
import com.multi.vidulum.okx.exchange_connection.infrastructure.ExchangeConnectionEntity;
import com.multi.vidulum.okx.exchange_connection.infrastructure.ExchangeConnectionMongoRepository;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistence tests against a real MongoDB.
 *
 * <p>The round trip is already covered without a database in {@code ExchangeConnectionEntityTest};
 * what needs a real server is the part the mapping cannot prove — that the natural key is
 * actually enforced by an index, and that the derived queries the synchronisation engine depends
 * on resolve the way their names suggest.
 */
@Slf4j
@SpringBootTest(
        classes = OkxPersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ExchangeConnectionPersistenceTest {

    private static final MongoDBContainer MONGO;

    static {
        MONGO = new MongoDBContainer("mongo:8.0.4");
        MONGO.start();
    }

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
    }

    private static final ZonedDateTime DAY_1 = ZonedDateTime.parse("2022-01-01T00:00:00Z");
    private static final ZonedDateTime DAY_2 = ZonedDateTime.parse("2022-01-02T00:00:00Z");
    private static final ZonedDateTime DAY_3 = ZonedDateTime.parse("2022-01-03T00:00:00Z");

    private static final UserId ALICE = UserId.of("U10000001");
    private static final UserId BOB = UserId.of("U10000002");
    private static final String ACCOUNT_UID = "349378528917283";

    @Autowired
    private DomainExchangeConnectionRepository repository;

    @Autowired
    private ExchangeConnectionMongoRepository mongoRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void clearCollection() {
        mongoRepository.deleteAll();
    }

    private static ExchangeConnection connection(
            String id, UserId user, ExchangeEnvironment environment, String accountUid) {
        return ExchangeConnection.pending(
                ExchangeConnectionId.of(id),
                user,
                Exchange.OKX,
                accountUid,
                environment,
                ExchangeRegion.EEA,
                ReportedKeyPermissions.of("read_only"),
                CredentialsMode.EXTERNAL,
                Currency.of("EUR"),
                DAY_1);
    }

    @Test
    void shouldStoreAndReadBackTheWholeConnection() {
        ExchangeConnection saved = connection("conn-1", ALICE, ExchangeEnvironment.DEMO, ACCOUNT_UID);
        saved.confirm(PortfolioId.of("portfolio-1"), DAY_2);
        saved.recordSnapshot(DAY_3);

        repository.save(saved);

        Optional<ExchangeConnection> found = repository.findById(ExchangeConnectionId.of("conn-1"));

        assertThat(found).isPresent();
        assertThat(found.get()).usingRecursiveComparison().isEqualTo(saved);
    }

    @Test
    void shouldFindConnectionByNaturalKey() {
        repository.save(connection("conn-1", ALICE, ExchangeEnvironment.DEMO, ACCOUNT_UID));

        Optional<ExchangeConnection> found = repository.findByAccount(
                ALICE, Exchange.OKX, ExchangeEnvironment.DEMO, ACCOUNT_UID);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(ExchangeConnectionId.of("conn-1"));
        assertThat(found.get().getStatus()).isEqualTo(ConnectionStatus.PENDING);
    }

    @Test
    void shouldNotMatchTheSameAccountInAnotherEnvironment() {
        repository.save(connection("conn-1", ALICE, ExchangeEnvironment.DEMO, ACCOUNT_UID));

        Optional<ExchangeConnection> found = repository.findByAccount(
                ALICE, Exchange.OKX, ExchangeEnvironment.LIVE, ACCOUNT_UID);

        assertThat(found).isEmpty();
    }

    @Test
    void shouldRejectConnectingTheSameAccountTwice() {
        repository.save(connection("conn-1", ALICE, ExchangeEnvironment.DEMO, ACCOUNT_UID));

        ExchangeConnection duplicate =
                connection("conn-2", ALICE, ExchangeEnvironment.DEMO, ACCOUNT_UID);

        assertThatThrownBy(() -> repository.save(duplicate))
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(mongoRepository.count()).isEqualTo(1);
    }

    /**
     * Demo and live are different accounts with different balances, so they are allowed to
     * coexist even when the uid happens to repeat.
     */
    @Test
    void shouldAllowTheSameAccountUidInDemoAndLive() {
        repository.save(connection("conn-demo", ALICE, ExchangeEnvironment.DEMO, ACCOUNT_UID));
        repository.save(connection("conn-live", ALICE, ExchangeEnvironment.LIVE, ACCOUNT_UID));

        assertThat(repository.findByUserId(ALICE))
                .extracting(c -> c.getId().getId())
                .containsExactlyInAnyOrder("conn-demo", "conn-live");
    }

    /**
     * The natural key is scoped to the user. A global one would let the first person to connect
     * an account lock everyone else out of it, which is a support problem rather than a
     * safeguard — portfolios are per-user, so nothing is double counted.
     */
    @Test
    void shouldAllowTwoUsersToConnectTheSameExchangeAccount() {
        repository.save(connection("conn-alice", ALICE, ExchangeEnvironment.DEMO, ACCOUNT_UID));
        repository.save(connection("conn-bob", BOB, ExchangeEnvironment.DEMO, ACCOUNT_UID));

        assertThat(repository.findByUserId(ALICE)).hasSize(1);
        assertThat(repository.findByUserId(BOB)).hasSize(1);
    }

    @Test
    void shouldFindConnectionFeedingAGivenPortfolio() {
        ExchangeConnection connection =
                connection("conn-1", ALICE, ExchangeEnvironment.DEMO, ACCOUNT_UID);
        connection.confirm(PortfolioId.of("portfolio-1"), DAY_2);
        repository.save(connection);
        repository.save(connection("conn-2", ALICE, ExchangeEnvironment.LIVE, "999"));

        Optional<ExchangeConnection> found = repository.findByPortfolioId(PortfolioId.of("portfolio-1"));

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(ExchangeConnectionId.of("conn-1"));
        assertThat(repository.findByPortfolioId(PortfolioId.of("portfolio-unknown"))).isEmpty();
    }

    /**
     * A connection that comes back keeps its portfolio through the store, not only in memory.
     */
    @Test
    void shouldPersistReconnectWithoutLosingThePortfolio() {
        ExchangeConnection connection =
                connection("conn-1", ALICE, ExchangeEnvironment.DEMO, ACCOUNT_UID);
        connection.confirm(PortfolioId.of("portfolio-1"), DAY_2);
        connection.revoke("api key expired", DAY_3);
        repository.save(connection);

        ExchangeConnection reloaded = repository
                .findByAccount(ALICE, Exchange.OKX, ExchangeEnvironment.DEMO, ACCOUNT_UID)
                .orElseThrow();
        reloaded.reconnect(DAY_3);
        repository.save(reloaded);

        ExchangeConnection afterReconnect =
                repository.findById(ExchangeConnectionId.of("conn-1")).orElseThrow();

        assertThat(afterReconnect.getStatus()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(afterReconnect.getStatusReason()).isNull();
        assertThat(afterReconnect.getPortfolioId()).isEqualTo(PortfolioId.of("portfolio-1"));
        assertThat(mongoRepository.count()).isEqualTo(1);
    }

    /**
     * The read-only rule must not be bypassable through the database. A document written around
     * the aggregate — by a migration, a fixture, or a hand edit — is refused on the way back in
     * rather than quietly becoming a live connection with a key that could trade.
     */
    @Test
    void shouldRefuseToLoadStoredConnectionWhoseKeyCouldTrade() {
        ExchangeConnectionEntity smuggled = ExchangeConnectionEntity.from(
                connection("conn-1", ALICE, ExchangeEnvironment.LIVE, ACCOUNT_UID));
        smuggled.setReportedKeyPermissions("read_only,trade");
        mongoTemplate.save(smuggled);

        assertThatThrownBy(() -> repository.findById(ExchangeConnectionId.of("conn-1")))
                .isInstanceOf(KeyPermissionsTooBroadException.class);
    }

    /**
     * Guards the reason {@code ExchangeConnectionIndexInitializer} exists: automatic index
     * creation is off in this project, so an annotated index would never reach the server.
     */
    @Test
    void shouldHaveCreatedTheUniqueNaturalKeyIndex() {
        List<Document> indexes = mongoTemplate
                .getCollection(mongoTemplate.getCollectionName(ExchangeConnectionEntity.class))
                .listIndexes()
                .into(new java.util.ArrayList<>());

        Document naturalKey = indexes.stream()
                .filter(index -> "exchange_connection_natural_key".equals(index.getString("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("natural key index missing, found: " + indexes));

        assertThat(naturalKey.getBoolean("unique")).isTrue();
        assertThat(naturalKey.get("key", Document.class).keySet())
                .containsExactly("userId", "exchange", "environment", "accountUid");
    }
}
