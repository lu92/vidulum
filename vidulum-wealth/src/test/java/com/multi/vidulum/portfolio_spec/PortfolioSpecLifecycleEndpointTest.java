package com.multi.vidulum.portfolio_spec;

import com.multi.vidulum.TestAuthenticatedUser;
import com.multi.vidulum.WealthTestApplication;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.error.ApiError;
import com.multi.vidulum.common.error.ErrorCode;
import com.multi.vidulum.config.FixedClockConfig;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio_spec.app.PortfolioSpecDto;
import com.multi.vidulum.portfolio_spec.domain.AnswerKind;
import com.multi.vidulum.quotation.app.QuotationDto;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.ZonedDateTime;
import java.util.List;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The specification lifecycle as a client meets it (tasks D10, D11, D13, C15).
 *
 * <p>The rules themselves are proven in milliseconds by {@code SpecLifecycleComponentTest} and
 * {@code SecondSynchronisationComponentTest}. What only a running server can show is the part
 * those cannot reach: that each outcome arrives as the right HTTP status with a code a client can
 * branch on, that the payload survives serialisation, and — the reason this class exists — that a
 * decision made while refusing a request is still there on the <b>next</b> request.
 *
 * <p>That last one is not hypothetical. {@code STALE} was being set on the aggregate and thrown
 * away with the request, because nothing saved it; every test that held the object in memory
 * agreed it worked. A live run found it, and this is the level that would have.
 */
@Slf4j
@SpringBootTest(
        classes = {WealthTestApplication.class, FixedClockConfig.class,
                PortfolioSpecLifecycleEndpointTest.OpenToEveryCaller.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Production's chain matches every request and would answer 403 to all of this. Switched
        // off by its own flag rather than worked around: who may call an endpoint is a question
        // this class does not ask, and ownership — the check that does matter here — is proven by
        // switching TestAuthenticatedUser.
        properties = "app.security.enabled=false")
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class PortfolioSpecLifecycleEndpointTest {

    /**
     * With the production chain off, Spring Boot would supply its own — which demands a password.
     * Declared as {@code @TestConfiguration} so component scanning never sees it and the module's
     * other tests keep the context they had.
     */
    @TestConfiguration
    static class OpenToEveryCaller {
        @Bean
        SecurityFilterChain permitAll(HttpSecurity http) throws Exception {
            return http
                    .csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                    .build();
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

    /** The clock is fixed, so "twenty minutes ago" is stated rather than waited for. */
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2022-01-01T00:00:00Z");
    private static final UserId ALICE = UserId.of("U10000001");
    private static final UserId BOB = UserId.of("U10000002");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TestAuthenticatedUser currentUser;

    private PortfolioSpecHttpActor actor;

    @BeforeEach
    void setUp() {
        currentUser.actAs(ALICE);
        actor = new PortfolioSpecHttpActor(restTemplate, port);
        actor.publishQuote("BINANCE", "BTC", "EUR", 50_000);
        actor.publishQuote("BINANCE", "EUR", "EUR", 1);
        // The exchange reports its cost in dollars whatever the account is valued in, so reading
        // the portfolio in euro needs the pair that restates it.
        actor.publishQuote("BINANCE", "USD", "EUR", 0.9);
        // Publishing goes through Kafka, so the cache fills a moment later — and confirmation
        // needs the price to value the opening contribution (C12). Waiting here rather than in
        // each test keeps the failure "no quote yet" from masquerading as a lifecycle bug.
        actor.registerAssetInfo("BINANCE", QuotationDto.AssetBasicInfoJson.builder()
                .ticker("BTC").fullName("Bitcoin").segment("Crypto").tags(List.of()).build());
        actor.registerAssetInfo("BINANCE", QuotationDto.AssetBasicInfoJson.builder()
                .ticker("EUR").fullName("Euro").segment("Cash").tags(List.of()).build());
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

    private PortfolioSpecDto.PortfolioSpecJson createSpec(ZonedDateTime takenAt) {
        ResponseEntity<PortfolioSpecDto.PortfolioSpecJson> response = actor.create(
                new PortfolioSpecDto.CreateSpecJson(
                        "BINANCE", null, "EUR", null, takenAt, List.of(btc(1.3, 0.3))));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private static PortfolioSpecDto.AnswerSpecJson unknownCost() {
        return new PortfolioSpecDto.AnswerSpecJson(List.of(
                new PortfolioSpecDto.GivenAnswerJson(
                        "BTC", "transferred-in", Quantity.of(1.0), AnswerKind.COST_UNKNOWN, null)));
    }

    private static PortfolioSpecDto.ConfirmSpecJson confirmWith(double total, double traded) {
        return new PortfolioSpecDto.ConfirmSpecJson(
                "My OKX", "EUR", "BINANCE", NOW, List.of(btc(total, traded)));
    }

    // --- the whole path, over the wire ----------------------------------------------------------

    @Test
    void shouldCarryASynchronisationFromQuestionsToAPortfolio() {
        PortfolioSpecDto.PortfolioSpecJson created = createSpec(NOW);
        assertThat(created.status()).isEqualTo("AWAITING_ANSWER");
        assertThat(created.snapshotExpired()).isFalse();
        assertThat(created.differences())
                .as("the exchange priced one part and not the other, so exactly one is a question")
                .anySatisfy(difference -> assertThat(difference.question()).isNotNull());

        ResponseEntity<PortfolioSpecDto.PortfolioSpecJson> answered =
                actor.answer(created.id(), unknownCost());
        assertThat(answered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(answered.getBody().status()).isEqualTo("CONFIRMED");

        ResponseEntity<PortfolioSpecDto.PortfolioSpecJson> applied =
                actor.confirm(created.id(), confirmWith(1.3, 0.3));
        assertThat(applied.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(applied.getBody().status()).isEqualTo("APPLIED");
        assertThat(applied.getBody().portfolioId()).isNotBlank();

        // C15, as a client sees it: two rows of one ticker, each saying which it is.
        ResponseEntity<PortfolioDto.PortfolioSummaryJson> portfolio =
                actor.readPortfolio(applied.getBody().portfolioId(), "EUR");
        assertThat(portfolio.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(portfolio.getBody().getAssets())
                .extracting(PortfolioDto.AssetSummaryJson::getTicker,
                        PortfolioDto.AssetSummaryJson::getSubName)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("BTC", "traded"),
                        org.assertj.core.groups.Tuple.tuple("BTC", "transferred-in"));
    }

    // --- D10: the anchor ages out ---------------------------------------------------------------

    /**
     * The regression this class was written for. The refusal decides something — this
     * specification is stale — and that has to survive the response.
     */
    @Test
    void shouldRefuseAnAnswerOnAnAgedAnchorAndRememberItOnTheNextRequest() {
        PortfolioSpecDto.PortfolioSpecJson stale = createSpec(NOW.minusMinutes(20));
        assertThat(stale.snapshotExpired()).isTrue();

        ResponseEntity<ApiError> refused = actor.answerExpectingError(stale.id(), unknownCost());

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().code()).isEqualTo(ErrorCode.PORTFOLIO_SPEC_SNAPSHOT_EXPIRED.name());
        assertThat(refused.getBody().message()).contains("read the account again");

        assertThat(actor.get(stale.id()).getBody().status())
                .as("read back on a new request — the status outlives the one that was refused")
                .isEqualTo("STALE");
    }

    // --- D10: the owner walks away ---------------------------------------------------------------

    @Test
    void shouldLetTheOwnerAbandonASynchronisationAndRefuseToApplyItAfterwards() {
        PortfolioSpecDto.PortfolioSpecJson created = createSpec(NOW);

        ResponseEntity<PortfolioSpecDto.PortfolioSpecJson> cancelled = actor.cancel(created.id());
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().status()).isEqualTo("CANCELLED");

        ResponseEntity<ApiError> tooLate = actor.confirmExpectingError(created.id(), confirmWith(1.3, 0.3));
        assertThat(tooLate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(tooLate.getBody().code())
                .isEqualTo(ErrorCode.PORTFOLIO_SPEC_INVALID_TRANSITION.name());
    }

    @Test
    void shouldAnswerNotFoundWhenSomebodyElseTriesToCancelIt() {
        PortfolioSpecDto.PortfolioSpecJson created = createSpec(NOW);
        currentUser.actAs(BOB);

        ResponseEntity<ApiError> refused = actor.cancelExpectingError(created.id());

        assertThat(refused.getStatusCode())
                .as("another user's specification does not exist, as far as they are concerned")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- D10 + D11: the account moves under it ----------------------------------------------------

    /**
     * Confirming against a changed account is refused, the specification is recomputed — and the
     * answer whose batch survived is still answered when the client looks again.
     */
    @Test
    void shouldRecomputeOnAMovedAccountAndKeepTheAnswersThatStillHold() {
        PortfolioSpecDto.PortfolioSpecJson created = createSpec(NOW);
        actor.answer(created.id(), unknownCost());

        ResponseEntity<ApiError> refused =
                actor.confirmExpectingError(created.id(), confirmWith(1.5, 0.5));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().code())
                .isEqualTo(ErrorCode.PORTFOLIO_SPEC_SNAPSHOT_CHANGED.name());

        PortfolioSpecDto.PortfolioSpecJson recomputed = actor.get(created.id()).getBody();
        assertThat(recomputed.differences())
                .filteredOn(difference -> difference.subName().equals("transferred-in"))
                .singleElement()
                .satisfies(difference -> assertThat(difference.answerKind())
                        .as("the batch did not move, so neither did the decision about it")
                        .isEqualTo("COST_UNKNOWN"));
        assertThat(recomputed.snapshotExpired())
                .as("the anchor was replaced, so the clock runs from the newer reading")
                .isFalse();
        assertThat(recomputed.status()).isEqualTo("CONFIRMED");
    }

    @Test
    void shouldAnswerNotFoundForSomebodyElsesSpecification() {
        PortfolioSpecDto.PortfolioSpecJson created = createSpec(NOW);
        currentUser.actAs(BOB);

        assertThat(actor.getExpectingError(created.id()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
