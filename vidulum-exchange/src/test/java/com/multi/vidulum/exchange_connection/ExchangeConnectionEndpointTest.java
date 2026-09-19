package com.multi.vidulum.exchange_connection;

import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.error.ApiError;
import com.multi.vidulum.common.error.ErrorCode;
import com.multi.vidulum.exchange_connection.app.ExchangeConnectionDto;
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
 * What only a running server can show: that each outcome reaches the client as the right HTTP
 * status with a machine-readable code, and that the payload survives serialisation.
 *
 * <p>The exhaustive payload assertions live in
 * {@code ExchangeConnectionControllerComponentTest}, which runs the same path in milliseconds
 * without a container. Duplicating them here would only slow the suite down — so this test keeps
 * one whole-object comparison to prove the JSON round trip and spends the rest on status codes.
 *
 * <p>All HTTP goes through {@link ExchangeConnectionHttpActor}, as {@code CLAUDE.md} requires.
 */
@Slf4j
@SpringBootTest(
        classes = ExchangeTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class ExchangeConnectionEndpointTest {

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
    private static final ZonedDateTime FIXED_NOW = ZonedDateTime.parse("2022-01-01T00:00:00Z");
    private static final String ACCOUNT_UID = "349378528917283";

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

    private static ExchangeConnectionDto.ConnectExchangeJson request(String broker, String region, String permissions) {
        return new ExchangeConnectionDto.ConnectExchangeJson(
                broker, ACCOUNT_UID, ExchangeEnvironment.DEMO, region, permissions, "EUR", null);
    }

    private static ExchangeConnectionDto.ConnectExchangeJson validRequest() {
        return request("DEMOEX", "EU", "read_only");
    }

    /**
     * One whole-object assertion over the wire: every field has to survive serialisation, not
     * just the ones a test remembered to check.
     */
    @Test
    void shouldCreateConnectionAndReturnItsWholeStateOverTheWire() {
        ResponseEntity<ExchangeConnectionDto.ExchangeConnectionJson> response = actor.connect(validRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ExchangeConnectionDto.ExchangeConnectionJson created = response.getBody();
        assertThat(created).isNotNull();
        assertThat(created.id()).isNotBlank();
        assertThat(created)
                .usingRecursiveComparison()
                .isEqualTo(new ExchangeConnectionDto.ExchangeConnectionJson(
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
                        FIXED_NOW));

        assertThat(mongoRepository.count()).isEqualTo(1);
    }

    /**
     * The gap this task closed: a second registration used to reach MongoDB's unique index and
     * come back as a 500.
     */
    @Test
    void shouldAnswerConflictWhenTheAccountIsAlreadyConnected() {
        actor.connect(validRequest());

        ResponseEntity<ApiError> response = actor.connectExpectingError(validRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code())
                .isEqualTo(ErrorCode.EXCHANGE_ACCOUNT_ALREADY_CONNECTED.name());
        assertThat(response.getBody().message()).contains(ACCOUNT_UID);
        assertThat(mongoRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldAnswerUnprocessableWhenTheKeyCouldTrade() {
        ResponseEntity<ApiError> response =
                actor.connectExpectingError(request("DEMOEX", "EU", "read_only,trade"));

        // Compared by value: Spring 7 renamed the 422 constant to UNPROCESSABLE_CONTENT, and the
        // two enum constants are not equal even though the status is the same.
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().code())
                .isEqualTo(ErrorCode.EXCHANGE_KEY_PERMISSIONS_TOO_BROAD.name());
        assertThat(mongoRepository.count()).isZero();
    }

    @Test
    void shouldAnswerBadRequestForAnUnsupportedExchange() {
        ResponseEntity<ApiError> response =
                actor.connectExpectingError(request("BINANCE", "EU", "read_only"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.EXCHANGE_NOT_SUPPORTED.name());
    }

    @Test
    void shouldAnswerBadRequestForARegionTheExchangeDoesNotServe() {
        ResponseEntity<ApiError> response =
                actor.connectExpectingError(request("DEMOEX", "MARS", "read_only"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.EXCHANGE_REGION_UNKNOWN.name());
    }

    /**
     * A blank field must come back as a 400 naming the field. Without bean validation the
     * aggregate's own guard would fire instead and surface as a 500.
     */
    @Test
    void shouldAnswerBadRequestWithFieldErrorsForABlankAccountUid() {
        ResponseEntity<ApiError> response = actor.connectRawExpectingError("""
                {
                  "broker": "DEMOEX",
                  "accountUid": "  ",
                  "environment": "DEMO",
                  "region": "EU",
                  "reportedKeyPermissions": "read_only",
                  "denominationCurrency": "EUR"
                }
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.VALIDATION_ERROR.name());
        assertThat(response.getBody().fieldErrors())
                .extracting(error -> error.field())
                .containsExactly("accountUid");
    }

    @Test
    void shouldReconnectRevokedConnectionOverHttpKeepingItsPortfolio() {
        String id = actor.connect(validRequest()).getBody().id();

        ExchangeConnection connection = repository.findById(ExchangeConnectionId.of(id)).orElseThrow();
        connection.confirm(PortfolioId.of("portfolio-1"), FIXED_NOW);
        connection.revoke("api key expired", FIXED_NOW);
        repository.save(connection);

        ResponseEntity<ExchangeConnectionDto.ExchangeConnectionJson> response = actor.reconnect(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("ACTIVE");
        assertThat(response.getBody().portfolioId()).isEqualTo("portfolio-1");
        assertThat(response.getBody().statusReason()).isNull();
        assertThat(mongoRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldAnswerConflictWhenReconnectingAConnectionThatIsNotRevoked() {
        String id = actor.connect(validRequest()).getBody().id();

        ResponseEntity<ApiError> response = actor.reconnectExpectingError(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code())
                .isEqualTo(ErrorCode.EXCHANGE_CONNECTION_INVALID_TRANSITION.name());
    }

    /**
     * Another user's connection is invisible, not forbidden — a 403 would confirm the id exists.
     */
    @Test
    void shouldAnswerNotFoundWhenReconnectingSomeoneElsesConnection() {
        String id = actor.connect(validRequest()).getBody().id();

        ExchangeTestApplication.CurrentTestUser.set(BOB);
        ResponseEntity<ApiError> response = actor.reconnectExpectingError(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code())
                .isEqualTo(ErrorCode.EXCHANGE_CONNECTION_NOT_FOUND.name());
    }

    @Test
    void shouldAnswerNotFoundForAnUnknownConnection() {
        assertThat(actor.reconnectExpectingError("no-such-connection").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * The user id comes from the authenticated principal, never from the body.
     */
    @Test
    void shouldAttributeConnectionToTheAuthenticatedUser() {
        ExchangeTestApplication.CurrentTestUser.set(BOB);

        assertThat(actor.connect(validRequest()).getBody().userId()).isEqualTo(BOB.getId());
    }
}
