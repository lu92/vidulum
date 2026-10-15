package com.multi.vidulum.portfolio;

import com.multi.vidulum.TestAuthenticatedUser;
import com.multi.vidulum.WealthTestApplication;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.error.ApiError;
import com.multi.vidulum.common.error.ErrorCode;
import com.multi.vidulum.config.FixedClockConfig;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a portfolio's refusals look like from outside (task G6).
 *
 * <p>Six of them extended {@code RuntimeException}, so every one reached the client as **500** —
 * indistinguishable from the backend falling over. A client cannot act on that: "you do not hold
 * that" and "we crashed" call for different behaviour, and only one of them is worth retrying.
 *
 * <p>This is the level that can tell the difference at all, which is why the test lives here and
 * not beside the aggregate: in-process, every one of them is just an exception being thrown.
 */
@SpringBootTest(
        classes = {WealthTestApplication.class, FixedClockConfig.class,
                PortfolioRefusalEndpointTest.OpenToEveryCaller.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.security.enabled=false")
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class PortfolioRefusalEndpointTest {

    @TestConfiguration
    static class OpenToEveryCaller {
        @Bean
        SecurityFilterChain permitAll(HttpSecurity http) throws Exception {
            return http.csrf(csrf -> csrf.disable())
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

    private static final UserId ALICE = UserId.of("U10000001");
    private static final UserId BOB = UserId.of("U10000002");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TestAuthenticatedUser currentUser;

    private String portfolioId;

    @BeforeEach
    void openAPortfolio() {
        currentUser.actAs(ALICE);
        portfolioId = post("/portfolio", PortfolioDto.CreateEmptyPortfolioJson.builder()
                .name("Refusals").broker("PM").allowedDepositCurrency("USD").build(),
                PortfolioDto.PortfolioSummaryJson.class).getBody().getPortfolioId();
        post("/portfolio/deposit", PortfolioDto.DepositMoneyJson.builder()
                .portfolioId(portfolioId).money(Money.of(1_000, "USD")).build(), String.class);
    }

    private <T> ResponseEntity<T> post(String path, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange("http://localhost:" + port + path, HttpMethod.POST,
                new HttpEntity<>(body, headers), type);
    }

    private ResponseEntity<ApiError> get(String path) {
        return restTemplate.exchange("http://localhost:" + port + path, HttpMethod.GET,
                HttpEntity.EMPTY, ApiError.class);
    }

    /** The path C8 has just added: pricing a position the portfolio does not hold. */
    @Test
    void shouldAnswerNotFoundWhenPricingAPositionThatIsNotHeld() {
        ResponseEntity<ApiError> refused = post("/portfolio/asset/cost",
                PortfolioDto.StateCostJson.builder()
                        .portfolioId(portfolioId).ticker("XAU").subName("traded")
                        .avgPrice(Price.of(2_000, "USD")).build(),
                ApiError.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(refused.getBody().code()).isEqualTo(ErrorCode.PORTFOLIO_ASSET_NOT_FOUND.name());
    }

    @Test
    void shouldRefuseToWithdrawMoreThanIsHeld() {
        ResponseEntity<ApiError> refused = post("/portfolio/withdraw",
                PortfolioDto.WithdrawMoneyJson.builder()
                        .portfolioId(portfolioId).money(Money.of(5_000, "USD")).build(),
                ApiError.class);

        assertThat(refused.getStatusCode().value())
                .as("a refusal about the request, not a failure of the server")
                .isEqualTo(422);
        assertThat(refused.getBody().code())
                .isEqualTo(ErrorCode.PORTFOLIO_INSUFFICIENT_BALANCE.name());
    }

    @Test
    void shouldRefuseToReleaseUnitsThatWereNeverReserved() {
        ResponseEntity<ApiError> refused = post("/portfolio/asset/unlock",
                PortfolioDto.UnlockAssetJson.builder()
                        .portfolioId(portfolioId).ticker("USD").orderId("no-such-order")
                        .quantity(Quantity.of(100)).build(),
                ApiError.class);

        assertThat(refused.getStatusCode().value()).isEqualTo(422);
        assertThat(refused.getBody().code()).isEqualTo(ErrorCode.PORTFOLIO_CANNOT_UNLOCK.name());
    }

    /** Ownership still answers 404, the rule A9 settled — and now so does everything beside it. */
    @Test
    void shouldStillHideSomebodyElsesPortfolio() {
        currentUser.actAs(BOB);

        ResponseEntity<ApiError> refused = get("/portfolio/" + portfolioId + "/USD");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(refused.getBody().code()).isEqualTo(ErrorCode.PORTFOLIO_NOT_FOUND.name());
    }

    /** None of these is a 500 any more — which is the whole of G6 in one assertion. */
    @Test
    void shouldNeverAnswerRefusalsWithAServerError() {
        assertThat(post("/portfolio/asset/cost", PortfolioDto.StateCostJson.builder()
                .portfolioId(portfolioId).ticker("XAU").subName("traded")
                .avgPrice(Price.of(1, "USD")).build(), ApiError.class).getStatusCode().is5xxServerError())
                .isFalse();
        assertThat(post("/portfolio/withdraw", PortfolioDto.WithdrawMoneyJson.builder()
                .portfolioId(portfolioId).money(Money.of(9_999, "USD")).build(), ApiError.class)
                .getStatusCode().is5xxServerError()).isFalse();
    }
}
