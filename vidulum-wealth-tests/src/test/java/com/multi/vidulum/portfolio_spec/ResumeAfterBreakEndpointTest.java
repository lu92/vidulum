package com.multi.vidulum.portfolio_spec;

import com.multi.vidulum.TestAuthenticatedUser;
import com.multi.vidulum.WealthTestApplication;
import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.error.ApiError;
import com.multi.vidulum.common.error.ErrorCode;
import com.multi.vidulum.config.FixedClockConfig;
import com.multi.vidulum.exchange_connection.app.ExchangeConnectionDto;
import com.multi.vidulum.exchange_connection.api.spi.ExchangeAdapter;
import com.multi.vidulum.exchange_connection.domain.ExchangeEnvironment;
import com.multi.vidulum.exchange_connection.api.error.UnknownExchangeRegionException;
import com.multi.vidulum.portfolio_spec.app.PortfolioSpecDto;
import com.multi.vidulum.portfolio_spec.domain.AnswerKind;
import com.multi.vidulum.quotation.app.QuotationDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Resuming a connection, over the wire (tasks H1 and H3).
 *
 * <p>The level matters here for two reasons. Resuming spans <b>two modules</b> — the connection
 * lives in {@code vidulum-exchange}, the specification in {@code vidulum-portfolio} — and only a
 * running server proves they compose. And the freshness of the numbers is something a <b>client</b>
 * reads off the connection payload, so whether that payload lies is a question that exists only
 * out here.
 */
@SpringBootTest(
        classes = {WealthTestApplication.class, FixedClockConfig.class,
                ResumeAfterBreakEndpointTest.OpenToEveryCaller.class,
                ResumeAfterBreakEndpointTest.AnExchangeToConnectTo.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.security.enabled=false")
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class ResumeAfterBreakEndpointTest {

    @TestConfiguration
    static class OpenToEveryCaller {
        @Bean
        SecurityFilterChain permitAll(HttpSecurity http) throws Exception {
            return http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                    .build();
        }
    }

    /**
     * This module cannot depend on any exchange's module — the dependency runs the other way — so
     * the registry needs something to recognise. Named after no real exchange on purpose: what is
     * under test is that the flow works for <i>an</i> exchange.
     */
    @TestConfiguration
    static class AnExchangeToConnectTo {
        @Bean
        ExchangeAdapter demoExchange() {
            return new ExchangeAdapter() {
                @Override
                public Broker broker() {
                    return Broker.of("BINANCE");
                }

                @Override
                public void validateRegion(String region) {
                    if (!"EU".equals(region == null ? null : region.trim().toUpperCase(Locale.ROOT))) {
                        throw new UnknownExchangeRegionException(broker(), region, List.of("EU"));
                    }
                }
            };
        }
    }

    private static final MongoDBContainer MONGO;
    private static final KafkaContainer KAFKA;

    static {
        MONGO = new MongoDBContainer("mongo:8.0.4");
        MONGO.start();
        KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.1"));
        KAFKA.start();
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        registry.add("mongodb.port", MONGO::getFirstMappedPort);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    private static final UserId ALICE = UserId.of("U10000001");
    private static final UserId BOB = UserId.of("U10000002");
    private static final ZonedDateTime ONBOARDED = ZonedDateTime.parse("2022-01-01T00:00:00Z");
    private static final ZonedDateTime RETURNED = ZonedDateTime.parse("2022-05-01T00:00:00Z");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TestAuthenticatedUser currentUser;

    private PortfolioSpecHttpActor actor;
    private String accountUid;

    @BeforeEach
    void setUp() {
        currentUser.actAs(ALICE);
        actor = new PortfolioSpecHttpActor(restTemplate, port);
        accountUid = "uid-" + System.nanoTime();
        actor.publishQuote("BINANCE", "BTC", "EUR", 50_000);
        actor.publishQuote("BINANCE", "EUR", "EUR", 1);
        actor.publishQuote("BINANCE", "USD", "EUR", 0.9);
        actor.registerAssetInfo("BINANCE", QuotationDto.AssetBasicInfoJson.builder()
                .ticker("BTC").fullName("Bitcoin").segment("Crypto").tags(List.of()).build());
        await().atMost(30, SECONDS).untilAsserted(() ->
                assertThat(actor.readQuote("BINANCE", "BTC", "EUR").getStatusCode())
                        .isEqualTo(HttpStatus.OK));
    }

    // --- building blocks ------------------------------------------------------------------------

    private static PortfolioSpecDto.SnapshotPositionJson btc(double total, double traded) {
        return new PortfolioSpecDto.SnapshotPositionJson(
                "BTC", Quantity.of(total), Quantity.of(traded), Quantity.of(0),
                Price.of(50_000, "USD"));
    }

    private String registerConnection() {
        ResponseEntity<ExchangeConnectionDto.ExchangeConnectionJson> created =
                actor.registerConnection(new ExchangeConnectionDto.ConnectExchangeJson(
                                "BINANCE", accountUid, ExchangeEnvironment.DEMO, "EU",
                                "read_only", "EUR", null),
                        ExchangeConnectionDto.ExchangeConnectionJson.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().id();
    }

    /** Onboard the account, then cut the connection off — the state a break leaves behind. */
    private String onboardThenBreakOff() {
        String connectionId = registerConnection();

        PortfolioSpecDto.PortfolioSpecJson spec = actor.create(new PortfolioSpecDto.CreateSpecJson(
                "BINANCE", connectionId, "EUR", null, ONBOARDED, List.of(btc(1.3, 0.3)))).getBody();
        actor.answer(spec.id(), new PortfolioSpecDto.AnswerSpecJson(List.of(
                new PortfolioSpecDto.GivenAnswerJson(
                        "BTC", "transferred-in", Quantity.of(1.0),
                        AnswerKind.COST_UNKNOWN, null))));
        actor.confirm(spec.id(), new PortfolioSpecDto.ConfirmSpecJson(
                "My account", "EUR", "BINANCE", ONBOARDED, List.of(btc(1.3, 0.3))));

        ResponseEntity<ExchangeConnectionDto.ExchangeConnectionJson> revoked = actor.revokeConnection(
                connectionId, new ExchangeConnectionDto.RevokeConnectionJson("api key expired"),
                ExchangeConnectionDto.ExchangeConnectionJson.class);
        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.OK);
        return connectionId;
    }

    private ResponseEntity<PortfolioSpecDto.ResumeConnectionResultJson> resume(
            String connectionId, double total, double traded) {
        return actor.resume(connectionId, new PortfolioSpecDto.ResumeConnectionJson(
                "BINANCE", RETURNED, List.of(btc(total, traded))));
    }

    private ExchangeConnectionDto.ExchangeConnectionJson readConnection(String connectionId) {
        return actor.readConnection(connectionId,
                ExchangeConnectionDto.ExchangeConnectionJson.class).getBody();
    }

    // --- the whole path -------------------------------------------------------------------------

    /**
     * Resume answers with a specification measured against the portfolio that survived the break,
     * and the connection does not yet claim its numbers are current.
     */
    @Test
    void shouldAnswerWithASpecificationAndNotClaimFreshness() {
        String connectionId = onboardThenBreakOff();

        ResponseEntity<PortfolioSpecDto.ResumeConnectionResultJson> resumed =
                resume(connectionId, 2.0, 0.3);

        assertThat(resumed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resumed.getBody().nothingToSynchronise()).isFalse();
        assertThat(resumed.getBody().specification().differences())
                .as("what appeared during the break, not the whole holding")
                .singleElement()
                .satisfies(difference ->
                        assertThat(difference.quantity()).isEqualTo(Quantity.of(0.7)));

        ExchangeConnectionDto.ExchangeConnectionJson connection = readConnection(connectionId);
        assertThat(connection.status()).isEqualTo("ACTIVE");
        assertThat(connection.portfolioSyncedAt())
                .as("still the onboarding moment — nothing has been applied yet (H1)")
                .isEqualTo(ONBOARDED);
        // The server's clock is fixed here, so every status transition stamps the same instant and
        // the two fields happen to coincide. That they move independently is proven where the
        // times actually differ — see ResumeAfterBreakComponentTest. What this level is for is the
        // field reaching the client at all, and holding the snapshot's moment rather than "now".
        assertThat(connection.statusChangedAt()).isNotNull();
    }

    /** Confirming it moves the anchor, stamped with the snapshot that was applied. */
    @Test
    void shouldMoveTheAnchorOnlyWhenTheSpecificationIsConfirmed() {
        String connectionId = onboardThenBreakOff();
        String specId = resume(connectionId, 2.0, 0.3).getBody().specification().id();
        actor.answer(specId, new PortfolioSpecDto.AnswerSpecJson(List.of(
                new PortfolioSpecDto.GivenAnswerJson(
                        "BTC", "transferred-in", Quantity.of(0.7),
                        AnswerKind.COST_UNKNOWN, null))));

        ResponseEntity<PortfolioSpecDto.PortfolioSpecJson> applied = actor.confirm(specId,
                new PortfolioSpecDto.ConfirmSpecJson("My account", "EUR", "BINANCE", RETURNED,
                        List.of(btc(2.0, 0.3))));

        assertThat(applied.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readConnection(connectionId).portfolioSyncedAt()).isEqualTo(RETURNED);
    }

    /**
     * Nothing moved during the break: no specification, and the client is told so rather than
     * left to infer it from an absence. The synchronisation is still recorded — already being in
     * agreement is agreement.
     */
    @Test
    void shouldReportNothingToSynchroniseAndStillRecordTheSync() {
        String connectionId = onboardThenBreakOff();

        ResponseEntity<PortfolioSpecDto.ResumeConnectionResultJson> resumed =
                resume(connectionId, 1.3, 0.3);

        assertThat(resumed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resumed.getBody().nothingToSynchronise()).isTrue();
        assertThat(resumed.getBody().specification()).isNull();
        assertThat(readConnection(connectionId).portfolioSyncedAt()).isEqualTo(RETURNED);
    }

    // --- refusals -------------------------------------------------------------------------------

    /** A connection that was never confirmed has no portfolio to bring up to date. */
    @Test
    void shouldRefuseToResumeAConnectionThatWasNeverConfirmed() {
        String connectionId = registerConnection();

        ResponseEntity<ApiError> refused = actor.resumeExpectingError(connectionId,
                new PortfolioSpecDto.ResumeConnectionJson("BINANCE", RETURNED, List.of(btc(1.3, 0.3))));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().code())
                .isEqualTo(ErrorCode.EXCHANGE_CONNECTION_INVALID_TRANSITION.name());
    }

    /** Someone else's connection answers 404, the rule A9 settled — not 403. */
    @Test
    void shouldHideSomebodyElsesConnection() {
        String connectionId = onboardThenBreakOff();
        currentUser.actAs(BOB);

        ResponseEntity<ApiError> refused = actor.resumeExpectingError(connectionId,
                new PortfolioSpecDto.ResumeConnectionJson("BINANCE", RETURNED, List.of(btc(2.0, 0.3))));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        currentUser.actAs(ALICE);
        assertThat(readConnection(connectionId).status())
                .as("and the connection is left exactly as it was")
                .isEqualTo("REVOKED");
    }
}
