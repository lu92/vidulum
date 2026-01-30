package com.multi.vidulum.cashflow.app;

import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.error.ApiError;
import com.multi.vidulum.config.FixedClockConfig;
import com.multi.vidulum.portfolio.app.PortfolioAppConfig;
import com.multi.vidulum.trading.app.TradingAppConfig;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP integration tests for CashFlow error handling.
 * Tests that domain exceptions are properly mapped to HTTP responses with correct status codes and ApiError bodies.
 *
 * This class follows the pattern established in AuthenticationControllerTest.
 * New error handling tests should be added to this class in future pull requests.
 */
@Slf4j
@SpringBootTest(
        classes = {FixedClockConfig.class, CashFlowErrorHandlingTest.TestSecurityConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Import({PortfolioAppConfig.class, TradingAppConfig.class})
@Testcontainers
@DirtiesContext
class CashFlowErrorHandlingTest {

    /**
     * Test security configuration that disables authentication for HTTP integration tests.
     */
    @TestConfiguration
    static class TestSecurityConfig {
        @Bean
        @Order(1)
        public SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            http
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(req -> req.anyRequest().permitAll());
            return http.build();
        }
    }

    @Container
    public static KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));

    @Container
    protected static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:4.4.6");

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
        registry.add("mongodb.port", mongoDBContainer::getFirstMappedPort);
        registry.add("spring.kafka.bootstrap-servers", () -> kafka.getBootstrapServers());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    private CashFlowHttpActor actor;

    @BeforeEach
    void setUp() {
        actor = new CashFlowHttpActor(restTemplate, port);
    }

    @Nested
    @DisplayName("Attestation - Balance Mismatch Error")
    class AttestationBalanceMismatchError {

        @Test
        @DisplayName("Should return 409 CONFLICT with CASHFLOW_BALANCE_MISMATCH error when balance does not match")
        void shouldReturn409ConflictWhenBalanceMismatch() {
            // given - create CashFlow with history
            String userId = "errortest_" + UUID.randomUUID().toString().substring(0, 8);
            String cashFlowName = "Test Balance Mismatch CashFlow";
            YearMonth startPeriod = YearMonth.of(2021, 9);
            Money initialBalance = Money.of(1000, "USD");

            String cashFlowId = actor.createCashFlowWithHistory(userId, cashFlowName, startPeriod, initialBalance);

            // Create category and import a transaction
            actor.createCategory(cashFlowId, "Salary", Type.INFLOW);
            actor.importHistoricalTransaction(
                    cashFlowId,
                    "Salary",
                    "September Salary",
                    "Monthly salary payment",
                    Money.of(2000, "USD"),
                    Type.INFLOW,
                    ZonedDateTime.parse("2021-09-25T00:00:00Z"),
                    ZonedDateTime.parse("2021-09-25T00:00:00Z")
            );

            // Expected balance: 1000 (initial) + 2000 (inflow) = 3000 USD
            // But user tries to confirm with 5000 USD (mismatch!)
            Money wrongConfirmedBalance = Money.of(5000, "USD");

            // when - attest with wrong balance
            ResponseEntity<ApiError> response = actor.attestHistoricalImportExpectingError(
                    cashFlowId,
                    wrongConfirmedBalance,
                    false,  // forceAttestation
                    false   // createAdjustment
            );

            // then - should return 409 CONFLICT with proper error structure
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();

            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(409);
            assertThat(error.code()).isEqualTo("CASHFLOW_BALANCE_MISMATCH");
            assertThat(error.message()).contains(cashFlowName);  // Error message should include CashFlow name
            assertThat(error.message()).contains("5000");  // Confirmed balance
            assertThat(error.message()).contains("3000");  // Calculated balance
            assertThat(error.fieldErrors()).isNull();  // No field errors for business exception
            assertThat(error.timestamp()).isNotNull();

            log.info("Balance mismatch correctly returned 409 CONFLICT: code={}, message={}",
                    error.code(), error.message());
        }

        @Test
        @DisplayName("Should return 200 OK when attestation succeeds with correct balance")
        void shouldReturn200OkWhenBalanceMatches() {
            // given - create CashFlow with history
            String userId = "successtest_" + UUID.randomUUID().toString().substring(0, 8);
            String cashFlowName = "Test Successful Attestation CashFlow";
            YearMonth startPeriod = YearMonth.of(2021, 10);
            Money initialBalance = Money.of(500, "PLN");

            String cashFlowId = actor.createCashFlowWithHistory(userId, cashFlowName, startPeriod, initialBalance);

            // Create category and import a transaction
            actor.createCategory(cashFlowId, "Bonus", Type.INFLOW);
            actor.importHistoricalTransaction(
                    cashFlowId,
                    "Bonus",
                    "October Bonus",
                    "Performance bonus",
                    Money.of(1500, "PLN"),
                    Type.INFLOW,
                    ZonedDateTime.parse("2021-10-15T00:00:00Z"),
                    ZonedDateTime.parse("2021-10-15T00:00:00Z")
            );

            // Correct balance: 500 (initial) + 1500 (inflow) = 2000 PLN
            Money correctConfirmedBalance = Money.of(2000, "PLN");

            // when - attest with correct balance
            ResponseEntity<CashFlowDto.AttestHistoricalImportResponseJson> response = actor.attestHistoricalImport(
                    cashFlowId,
                    correctConfirmedBalance,
                    false,  // forceAttestation
                    false   // createAdjustment
            );

            // then - should return 200 OK
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getCashFlowId()).isEqualTo(cashFlowId);

            log.info("Attestation succeeded with correct balance: cashFlowId={}", cashFlowId);
        }
    }
}
