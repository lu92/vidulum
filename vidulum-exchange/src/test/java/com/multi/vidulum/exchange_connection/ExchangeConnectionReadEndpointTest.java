package com.multi.vidulum.exchange_connection;

import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.error.ApiError;
import com.multi.vidulum.common.error.ErrorCode;
import com.multi.vidulum.exchange_connection.app.ConnectExchangeRequest;
import com.multi.vidulum.exchange_connection.app.ExchangeConnectionJson;
import com.multi.vidulum.exchange_connection.app.ExchangeConnectionsListJson;
import com.multi.vidulum.exchange_connection.domain.DomainExchangeConnectionRepository;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnection;
import com.multi.vidulum.exchange_connection.domain.ExchangeConnectionId;
import com.multi.vidulum.exchange_connection.domain.ExchangeEnvironment;
import com.multi.vidulum.exchange_connection.infrastructure.ExchangeConnectionMongoRepository;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading connections over HTTP.
 *
 * <p>Field-by-field state is covered without a container in
 * {@code ExchangeConnectionControllerComponentTest}; what needs a server is that the values
 * survive JSON — in particular the two timestamps, which must arrive as two separate fields
 * because they drift apart in both directions. Balances read three days ago and priced five
 * seconds ago look fresh if the two are collapsed into one "updated at", and the interface then
 * has no way to say which of them is the stale one.
 */
@Slf4j
@SpringBootTest(
        classes = ExchangeTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class ExchangeConnectionReadEndpointTest {

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

    private static final UserId ALICE = UserId.of("U10000001");
    private static final UserId BOB = UserId.of("U10000002");
    private static final ZonedDateTime SNAPSHOT_AT = ZonedDateTime.parse("2022-03-04T10:15:30Z");
    private static final ZonedDateTime SYNC_AT = ZonedDateTime.parse("2022-03-01T08:00:00Z");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ExchangeConnectionMongoRepository mongoRepository;

    @Autowired
    private DomainExchangeConnectionRepository repository;

    private ExchangeConnectionHttpActor actor;

    @BeforeEach
    void reset() {
        mongoRepository.deleteAll();
        ExchangeTestApplication.CurrentTestUser.set(ALICE);
        actor = new ExchangeConnectionHttpActor(restTemplate, port);
    }

    private ExchangeConnectionJson connectAccount(String accountUid) {
        return actor.connect(new ConnectExchangeRequest(
                "DEMOEX", accountUid, ExchangeEnvironment.DEMO, "EU", "read_only", "EUR", null))
                .getBody();
    }

    private String connect(String accountUid) {
        return connectAccount(accountUid).id();
    }

    /**
     * Reading a connection back must return exactly what creating it returned — every field, not
     * the handful a test remembered to check.
     */
    @Test
    void shouldReturnTheSameConnectionThatWasJustCreated() {
        ExchangeConnectionJson created = connectAccount("349378528917283");

        ResponseEntity<ExchangeConnectionJson> response = actor.get(created.id());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .usingRecursiveComparison()
                .isEqualTo(created);
    }

    /**
     * The reason {@code A9} is its own task: two clocks, serialised separately.
     */
    @Test
    void shouldReturnBothStalenessTimestampsSeparately() {
        String id = connect("349378528917283");

        ExchangeConnection connection = repository.findById(ExchangeConnectionId.of(id)).orElseThrow();
        connection.confirm(PortfolioId.of("portfolio-1"), SYNC_AT);
        connection.recordSnapshot(SNAPSHOT_AT);
        repository.save(connection);

        ExchangeConnectionJson read = actor.get(id).getBody();

        assertThat(read.lastSnapshotAt()).isEqualTo(SNAPSHOT_AT);
        assertThat(read.lastSyncAt()).isEqualTo(SYNC_AT);
        assertThat(read.lastSnapshotAt())
                .as("collapsing the two into one field would hide which one is stale")
                .isNotEqualTo(read.lastSyncAt());
        assertThat(read.portfolioId()).isEqualTo("portfolio-1");
    }

    @Test
    void shouldExposeStatusReasonSoTheUserLearnsWhyAConnectionBroke() {
        String id = connect("349378528917283");

        ExchangeConnection connection = repository.findById(ExchangeConnectionId.of(id)).orElseThrow();
        connection.confirm(PortfolioId.of("portfolio-1"), SYNC_AT);
        connection.revoke("api key expired", SNAPSHOT_AT);
        repository.save(connection);

        ExchangeConnectionJson read = actor.get(id).getBody();

        assertThat(read.status()).isEqualTo("REVOKED");
        assertThat(read.statusReason()).isEqualTo("api key expired");
        assertThat(read.portfolioId())
                .as("a revoked connection keeps its portfolio")
                .isEqualTo("portfolio-1");
    }

    @Test
    void shouldListOnlyTheCallersConnectionsAlongsideSupportedExchanges() {
        connect("111");
        connect("222");
        ExchangeTestApplication.CurrentTestUser.set(BOB);
        connect("333");

        ExchangeTestApplication.CurrentTestUser.set(ALICE);
        ExchangeConnectionsListJson listed = actor.list().getBody();

        assertThat(listed.connections())
                .hasSize(2)
                .allSatisfy(connection ->
                        assertThat(connection.userId()).isEqualTo(ALICE.getId()))
                .extracting(ExchangeConnectionJson::accountUid)
                .containsExactlyInAnyOrder("111", "222");
        assertThat(listed.supportedExchanges()).containsExactly("DEMOEX");
    }

    @Test
    void shouldReturnAnEmptyListForAUserWithNoConnections() {
        ExchangeConnectionsListJson listed = actor.list().getBody();

        assertThat(listed)
                .usingRecursiveComparison()
                .isEqualTo(new ExchangeConnectionsListJson(java.util.List.of(), java.util.List.of("DEMOEX")));
    }

    @Test
    void shouldHideAnotherUsersConnectionBehindNotFound() {
        String id = connect("349378528917283");

        ExchangeTestApplication.CurrentTestUser.set(BOB);
        ResponseEntity<ApiError> response = actor.getExpectingError(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code())
                .isEqualTo(ErrorCode.EXCHANGE_CONNECTION_NOT_FOUND.name());
    }

    @Test
    void shouldAnswerNotFoundForAnUnknownConnection() {
        ResponseEntity<ApiError> response = actor.getExpectingError("no-such-connection");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code())
                .isEqualTo(ErrorCode.EXCHANGE_CONNECTION_NOT_FOUND.name());
    }
}
