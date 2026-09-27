package com.multi.vidulum.portfolio;

import com.multi.vidulum.TestAuthenticatedUser;
import com.multi.vidulum.WealthTestApplication;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.Price;
import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.config.FixedClockConfig;
import com.multi.vidulum.portfolio.app.PortfolioDto;
import com.multi.vidulum.portfolio.domain.portfolio.ProfitStatus;
import com.multi.vidulum.portfolio.domain.portfolio.ValuationStatus;
import com.multi.vidulum.trading.app.TradingDto;
import org.awaitility.Awaitility;
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

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a portfolio that holds something nobody quotes (task C16).
 *
 * <p>This is the level where the defect existed at all: in-process it was an exception like any
 * other, and over HTTP it was a <b>404 on the whole portfolio</b> — the owner asked what they hold
 * and were told, in effect, that they hold nothing. One position the exchange does not quote is
 * enough, and the funding account (E10) is full of candidates.
 */
@SpringBootTest(
        classes = {WealthTestApplication.class, FixedClockConfig.class,
                UnpricedPositionEndpointTest.OpenToEveryCaller.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.security.enabled=false")
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class UnpricedPositionEndpointTest {

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

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TestAuthenticatedUser currentUser;

    private String portfolioId;

    /** Ten thousand dollars, two ounces of something nobody has published a price for. */
    @BeforeEach
    void holdSomethingNobodyQuotes() {
        currentUser.actAs(ALICE);
        portfolioId = post("/portfolio", PortfolioDto.CreateEmptyPortfolioJson.builder()
                .name("Funding leftovers").broker("PM").allowedDepositCurrency("USD").build(),
                PortfolioDto.PortfolioSummaryJson.class).getBody().getPortfolioId();
        post("/portfolio/deposit", PortfolioDto.DepositMoneyJson.builder()
                .portfolioId(portfolioId).money(Money.of(10_000, "USD")).build(), String.class);

        post("/trades", TradingDto.TradeExecutedJson.builder()
                .portfolioId(portfolioId)
                .originTradeId("trade-1")
                .symbol("XAU/USD")
                .subName("traded")
                .side(com.multi.vidulum.common.Side.BUY)
                .quantity(Quantity.of(2))
                .price(Price.of(2_000, "USD"))
                .fee(TradingDto.Fee.builder()
                        .exchangeCurrencyFee(Money.zero("USD"))
                        .transactionFee(Money.zero("USD"))
                        .build())
                .build(), TradingDto.TradeSummaryJson.class);

        // The trade reaches the portfolio through Kafka, so the position appears a moment later.
        // That the read works at all while waiting is itself the point: before C16 this call
        // answered 404 from the instant the gold arrived.
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(read("USD").getBody().getAssets()).hasSize(2));
    }

    private <T> ResponseEntity<T> post(String path, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange("http://localhost:" + port + path, HttpMethod.POST,
                new HttpEntity<>(body, headers), type);
    }

    private ResponseEntity<PortfolioDto.PortfolioSummaryJson> read(String currency) {
        return restTemplate.exchange("http://localhost:" + port + "/portfolio/" + portfolioId + "/" + currency,
                HttpMethod.GET, HttpEntity.EMPTY, PortfolioDto.PortfolioSummaryJson.class);
    }

    /** The assertion the task exists for: an answer, not a refusal. */
    @Test
    void shouldAnswerTheReadInsteadOfRefusingTheWholePortfolio() {
        ResponseEntity<PortfolioDto.PortfolioSummaryJson> response = read("USD");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getAssets())
                .as("both positions are reported, priced or not")
                .hasSize(2);
    }

    /** The gap travels over the wire: the row is there, its value is not, and it says which. */
    @Test
    void shouldSerialiseThePositionAsARowWithoutAValue() {
        PortfolioDto.AssetSummaryJson gold = read("USD").getBody().getAssets().stream()
                .filter(asset -> asset.getTicker().equals("XAU"))
                .findFirst().orElseThrow();

        assertThat(gold.getQuantity()).isEqualTo(Quantity.of(2));
        assertThat(gold.getCurrentPrice()).isNull();
        assertThat(gold.getCurrentValue()).isNull();
        assertThat(gold.isPriceUnknown()).isTrue();
    }

    /** And the total says what it is a total of, naming what it left out. */
    @Test
    void shouldSayThatTheTotalIsPartialAndNameWhatIsMissing() {
        PortfolioDto.PortfolioSummaryJson summary = read("USD").getBody();

        assertThat(summary.getValuationStatus()).isEqualTo(ValuationStatus.PARTIAL);
        assertThat(summary.getUnpricedAssets()).containsExactly("XAU/traded");
        assertThat(summary.getCurrentValue())
                .as("the cash that was left after buying the gold")
                .isEqualTo(Money.of(6_000, "USD"));
        assertThat(summary.getWealthChange()).isNull();
        assertThat(summary.getProfitStatus()).isEqualTo(ProfitStatus.WITHHELD_UNPRICED_POSITIONS);
    }

    /**
     * Publishing the missing quote repairs it with no further action — the silence was about the
     * price being absent, never about the position being damaged.
     */
    @Test
    void shouldValueTheWholePortfolioOncePriceArrives() {
        restTemplate.getForEntity("http://localhost:" + port
                + "/quote/publish?broker=PM&origin=XAU&destination=USD&amount=2100&currency=USD&pctChange=0",
                Void.class);

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            PortfolioDto.PortfolioSummaryJson summary = read("USD").getBody();
            assertThat(summary.getValuationStatus()).isEqualTo(ValuationStatus.COMPLETE);
            assertThat(summary.getUnpricedAssets()).isEmpty();
            assertThat(summary.getCurrentValue()).isEqualTo(Money.of(10_200, "USD"));
            assertThat(summary.getWealthChange())
                    .as("the comparison against contributions is sound again")
                    .isEqualTo(Money.of(200, "USD"));
        });
    }
}
