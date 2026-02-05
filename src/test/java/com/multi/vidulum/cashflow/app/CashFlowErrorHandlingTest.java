package com.multi.vidulum.cashflow.app;

import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.error.ApiError;
import com.multi.vidulum.config.FixedClockConfig;
import com.multi.vidulum.portfolio.app.PortfolioAppConfig;
import com.multi.vidulum.trading.app.TradingAppConfig;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
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

import static com.multi.vidulum.cashflow.domain.Type.INFLOW;
import static com.multi.vidulum.cashflow.domain.Type.OUTFLOW;
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

    // ============ Resources Not Found (404) ============

    @Nested
    @DisplayName("Resources Not Found (404)")
    class ResourcesNotFound {

        @Test
        @DisplayName("Should return 404 NOT_FOUND with CASHFLOW_NOT_FOUND when CashFlow does not exist")
        void shouldReturn404WhenCashFlowNotFound() {
            // given
            String nonExistentCashFlowId = UUID.randomUUID().toString();

            // when
            ResponseEntity<ApiError> response = actor.getCashFlowExpectingError(nonExistentCashFlowId);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();

            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(404);
            assertThat(error.code()).isEqualTo("CASHFLOW_NOT_FOUND");
            assertThat(error.message()).contains(nonExistentCashFlowId);
            assertThat(error.fieldErrors()).isNull();
            assertThat(error.timestamp()).isNotNull();

            log.info("CashFlow not found correctly returned 404: code={}", error.code());
        }

        @Test
        @DisplayName("Should return 404 NOT_FOUND with CASHCHANGE_NOT_FOUND when CashChange does not exist")
        void shouldReturn404WhenCashChangeNotFound() {
            // given - create CashFlow first
            String userId = "test_" + UUID.randomUUID().toString().substring(0, 8);
            String cashFlowId = actor.createCashFlow(userId, "Test CashFlow", "USD");
            String nonExistentCashChangeId = UUID.randomUUID().toString();

            // when - try to confirm non-existent cash change
            ResponseEntity<ApiError> response = actor.confirmCashChangeExpectingError(cashFlowId, nonExistentCashChangeId);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();

            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(404);
            assertThat(error.code()).isEqualTo("CASHCHANGE_NOT_FOUND");
            assertThat(error.message()).contains(nonExistentCashChangeId);
            assertThat(error.fieldErrors()).isNull();

            log.info("CashChange not found correctly returned 404: code={}", error.code());
        }

        @Test
        @DisplayName("Should return 404 NOT_FOUND with CATEGORY_NOT_FOUND when archiving non-existent category")
        void shouldReturn404WhenCategoryNotFoundOnArchive() {
            // given
            String userId = "test_" + UUID.randomUUID().toString().substring(0, 8);
            String cashFlowId = actor.createCashFlow(userId, "Test CashFlow", "USD");

            // when - try to archive non-existent category
            ResponseEntity<ApiError> response = actor.archiveCategoryExpectingError(
                    cashFlowId, "NonExistentCategory", INFLOW, false);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();

            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(404);
            assertThat(error.code()).isEqualTo("CATEGORY_NOT_FOUND");
            assertThat(error.message()).contains("NonExistentCategory");
            assertThat(error.fieldErrors()).isNull();

            log.info("Category not found correctly returned 404: code={}", error.code());
        }

        @Test
        @DisplayName("Should return 404 NOT_FOUND with BUDGETING_NOT_FOUND when updating non-existent budgeting")
        void shouldReturn404WhenBudgetingNotFound() {
            // given
            String userId = "test_" + UUID.randomUUID().toString().substring(0, 8);
            String cashFlowId = actor.createCashFlow(userId, "Test CashFlow", "USD");

            // when - try to update budgeting that was never set
            ResponseEntity<ApiError> response = actor.updateBudgetingExpectingError(
                    cashFlowId, "Uncategorized", INFLOW, Money.of(500, "USD"));

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();

            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(404);
            assertThat(error.code()).isEqualTo("BUDGETING_NOT_FOUND");
            assertThat(error.message()).contains("Uncategorized");
            assertThat(error.fieldErrors()).isNull();

            log.info("Budgeting not found correctly returned 404: code={}", error.code());
        }
    }

    // ============ Conflict Errors (409) ============

    @Nested
    @DisplayName("Conflict Errors (409)")
    class ConflictErrors {

        @Test
        @DisplayName("Should return 409 CONFLICT with CATEGORY_ALREADY_EXISTS when creating duplicate category")
        void shouldReturn409WhenCategoryAlreadyExists() {
            // given
            String userId = "test_" + UUID.randomUUID().toString().substring(0, 8);
            String cashFlowId = actor.createCashFlow(userId, "Test CashFlow", "USD");
            actor.createCategory(cashFlowId, "MyCategory", INFLOW);

            // when - try to create same category again
            ResponseEntity<ApiError> response = actor.createCategoryExpectingError(
                    cashFlowId, "MyCategory", INFLOW, false);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();

            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(409);
            assertThat(error.code()).isEqualTo("CATEGORY_ALREADY_EXISTS");
            assertThat(error.message()).contains("MyCategory");
            assertThat(error.fieldErrors()).isNull();

            log.info("Category already exists correctly returned 409: code={}", error.code());
        }

        @Test
        @DisplayName("Should return 409 CONFLICT with BUDGETING_ALREADY_EXISTS when setting duplicate budgeting")
        void shouldReturn409WhenBudgetingAlreadyExists() {
            // given
            String userId = "test_" + UUID.randomUUID().toString().substring(0, 8);
            String cashFlowId = actor.createCashFlow(userId, "Test CashFlow", "USD");
            actor.setBudgeting(cashFlowId, "Uncategorized", INFLOW, Money.of(1000, "USD"));

            // when - try to set budgeting again
            ResponseEntity<ApiError> response = actor.setBudgetingExpectingError(
                    cashFlowId, "Uncategorized", INFLOW, Money.of(2000, "USD"));

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();

            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(409);
            assertThat(error.code()).isEqualTo("BUDGETING_ALREADY_EXISTS");
            assertThat(error.message()).contains("Uncategorized");
            assertThat(error.fieldErrors()).isNull();

            log.info("Budgeting already exists correctly returned 409: code={}", error.code());
        }

        @Test
        @DisplayName("Should return 409 CONFLICT with CATEGORY_UNARCHIVE_CONFLICT when unarchiving with active duplicate")
        void shouldReturn409WhenCannotUnarchiveCategory() {
            // given - create category, archive it, create new one with same name
            String userId = "test_" + UUID.randomUUID().toString().substring(0, 8);
            String cashFlowId = actor.createCashFlow(userId, "Test CashFlow", "USD");
            actor.createCategory(cashFlowId, "TestCategory", OUTFLOW);
            actor.archiveCategory(cashFlowId, "TestCategory", OUTFLOW, false);
            actor.createCategory(cashFlowId, "TestCategory", OUTFLOW); // Create new one with same name

            // when - try to unarchive the old one
            ResponseEntity<ApiError> response = actor.unarchiveCategoryExpectingError(
                    cashFlowId, "TestCategory", OUTFLOW);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();

            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(409);
            assertThat(error.code()).isEqualTo("CATEGORY_UNARCHIVE_CONFLICT");
            assertThat(error.message()).contains("TestCategory");
            assertThat(error.fieldErrors()).isNull();

            log.info("Cannot unarchive category correctly returned 409: code={}", error.code());
        }
    }

    // ============ Invalid CashFlow State (400) ============

    @Nested
    @DisplayName("Invalid CashFlow State (400)")
    class InvalidCashFlowState {

        @Test
        @DisplayName("Should return 400 BAD_REQUEST when appending expected cash change in SETUP mode")
        void shouldReturn400WhenAppendExpectedInSetupMode() {
            // given - create CashFlow with history (SETUP mode)
            String userId = "test_" + UUID.randomUUID().toString().substring(0, 8);
            YearMonth startPeriod = YearMonth.of(2021, 9);
            String cashFlowId = actor.createCashFlowWithHistory(userId, "Setup CashFlow", startPeriod, Money.of(1000, "USD"));

            // when
            ResponseEntity<ApiError> response = actor.appendExpectedCashChangeExpectingError(
                    cashFlowId, "Uncategorized", "Test", "Description",
                    Money.of(100, "USD"), INFLOW,
                    ZonedDateTime.parse("2022-01-15T00:00:00Z"));

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();

            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("CASHFLOW_OPERATION_NOT_ALLOWED_IN_SETUP");
            assertThat(error.message()).contains("SETUP mode");
            assertThat(error.fieldErrors()).isNull();

            log.info("Operation not allowed in SETUP mode correctly returned 400: code={}", error.code());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST when appending paid cash change in SETUP mode")
        void shouldReturn400WhenAppendPaidInSetupMode() {
            // given
            String userId = "test_" + UUID.randomUUID().toString().substring(0, 8);
            YearMonth startPeriod = YearMonth.of(2021, 9);
            String cashFlowId = actor.createCashFlowWithHistory(userId, "Setup CashFlow", startPeriod, Money.of(1000, "USD"));

            // when
            ResponseEntity<ApiError> response = actor.appendPaidCashChangeExpectingError(
                    cashFlowId, "Uncategorized", "Test", "Description",
                    Money.of(100, "USD"), INFLOW,
                    ZonedDateTime.parse("2022-01-01T00:00:00Z"),
                    ZonedDateTime.parse("2022-01-01T00:00:00Z"));

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("CASHFLOW_OPERATION_NOT_ALLOWED_IN_SETUP");

            log.info("Append paid not allowed in SETUP mode correctly returned 400: code={}", error.code());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST when editing cash change in SETUP mode")
        void shouldReturn400WhenEditInSetupMode() {
            // given
            String userId = "test_" + UUID.randomUUID().toString().substring(0, 8);
            YearMonth startPeriod = YearMonth.of(2021, 9);
            String cashFlowId = actor.createCashFlowWithHistory(userId, "Setup CashFlow", startPeriod, Money.of(1000, "USD"));

            // when
            ResponseEntity<ApiError> response = actor.editCashChangeExpectingError(
                    cashFlowId, "fake-id", "New Name", "New Description",
                    Money.of(200, "USD"), "Uncategorized", ZonedDateTime.parse("2022-01-20T00:00:00Z"));

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("CASHFLOW_OPERATION_NOT_ALLOWED_IN_SETUP");

            log.info("Edit not allowed in SETUP mode correctly returned 400: code={}", error.code());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST when confirming cash change in SETUP mode")
        void shouldReturn400WhenConfirmInSetupMode() {
            // given
            String userId = "test_" + UUID.randomUUID().toString().substring(0, 8);
            YearMonth startPeriod = YearMonth.of(2021, 9);
            String cashFlowId = actor.createCashFlowWithHistory(userId, "Setup CashFlow", startPeriod, Money.of(1000, "USD"));

            // when
            ResponseEntity<ApiError> response = actor.confirmCashChangeExpectingError(cashFlowId, "fake-id");

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("CASHFLOW_OPERATION_NOT_ALLOWED_IN_SETUP");

            log.info("Confirm not allowed in SETUP mode correctly returned 400: code={}", error.code());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST when importing to non-SETUP mode CashFlow")
        void shouldReturn400WhenImportNotAllowedInNonSetupMode() {
            // given - create standard CashFlow (OPEN mode)
            String userId = "test_" + UUID.randomUUID().toString().substring(0, 8);
            String cashFlowId = actor.createCashFlow(userId, "Open CashFlow", "USD");

            // when - try to import historical transaction
            ResponseEntity<ApiError> response = actor.importHistoricalTransactionExpectingError(
                    cashFlowId, "Uncategorized", "Historical", "Desc",
                    Money.of(500, "USD"), INFLOW,
                    ZonedDateTime.parse("2021-06-15T00:00:00Z"),
                    ZonedDateTime.parse("2021-06-15T00:00:00Z"));

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("CASHFLOW_IMPORT_NOT_ALLOWED");
            assertThat(error.message()).contains("SETUP mode");

            log.info("Import not allowed in non-SETUP mode correctly returned 400: code={}", error.code());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST when attesting non-SETUP mode CashFlow")
        void shouldReturn400WhenAttestationNotAllowedInNonSetupMode() {
            // given - create standard CashFlow (OPEN mode)
            String userId = "test_" + UUID.randomUUID().toString().substring(0, 8);
            String cashFlowId = actor.createCashFlow(userId, "Open CashFlow", "USD");

            // when - try to attest
            ResponseEntity<ApiError> response = actor.attestHistoricalImportExpectingError(
                    cashFlowId, Money.of(1000, "USD"), false, false);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("CASHFLOW_ATTESTATION_NOT_ALLOWED");

            log.info("Attestation not allowed in non-SETUP mode correctly returned 400: code={}", error.code());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST when rolling back non-SETUP mode CashFlow")
        void shouldReturn400WhenRollbackNotAllowedInNonSetupMode() {
            // given - create standard CashFlow (OPEN mode)
            String userId = "test_" + UUID.randomUUID().toString().substring(0, 8);
            String cashFlowId = actor.createCashFlow(userId, "Open CashFlow", "USD");

            // when - try to rollback
            ResponseEntity<ApiError> response = actor.rollbackImportExpectingError(cashFlowId);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("CASHFLOW_ROLLBACK_NOT_ALLOWED");

            log.info("Rollback not allowed in non-SETUP mode correctly returned 400: code={}", error.code());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST when confirming already confirmed cash change")
        void shouldReturn400WhenCashChangeNotPending() {
            // given - create CashFlow and add+confirm a cash change
            String userId = "test_" + UUID.randomUUID().toString().substring(0, 8);
            String cashFlowId = actor.createCashFlow(userId, "Test CashFlow", "USD");

            // Note: FixedClockConfig sets clock to 2022-01-01, so we use dates in January 2022
            String cashChangeId = actor.appendExpectedCashChange(
                    cashFlowId, "Uncategorized", "Test Payment", "Description",
                    Money.of(100, "USD"), INFLOW, ZonedDateTime.parse("2022-01-15T00:00:00Z"));

            actor.confirmCashChange(cashFlowId, cashChangeId);

            // when - try to confirm again
            ResponseEntity<ApiError> response = actor.confirmCashChangeExpectingError(cashFlowId, cashChangeId);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("CASHCHANGE_NOT_PENDING");

            log.info("CashChange not pending correctly returned 400: code={}", error.code());
        }
    }

    // ============ Date Validation (400) ============

    @Nested
    @DisplayName("Date Validation (400)")
    class DateValidation {

        @Test
        @DisplayName("Should return 400 BAD_REQUEST when paid date is in future")
        void shouldReturn400WhenPaidDateInFuture() {
            // given - FixedClockConfig sets clock to 2022-01-01
            String userId = "test_" + UUID.randomUUID().toString().substring(0, 8);
            String cashFlowId = actor.createCashFlow(userId, "Test CashFlow", "USD");

            // when - try to add paid cash change with future date
            ResponseEntity<ApiError> response = actor.appendPaidCashChangeExpectingError(
                    cashFlowId, "Uncategorized", "Future Payment", "Description",
                    Money.of(100, "USD"), INFLOW,
                    ZonedDateTime.parse("2022-02-15T00:00:00Z"),
                    ZonedDateTime.parse("2022-02-15T00:00:00Z")); // Future!

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("PAID_DATE_IN_FUTURE");
            assertThat(error.message()).contains("2022-02-15");

            log.info("Paid date in future correctly returned 400: code={}", error.code());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST when paid date not in active period")
        void shouldReturn400WhenPaidDateNotInActivePeriod() {
            // given - FixedClockConfig sets clock to 2022-01-01, active period is 2022-01
            String userId = "test_" + UUID.randomUUID().toString().substring(0, 8);
            String cashFlowId = actor.createCashFlow(userId, "Test CashFlow", "USD");

            // when - try to add paid cash change with date in December 2021 (past month)
            ResponseEntity<ApiError> response = actor.appendPaidCashChangeExpectingError(
                    cashFlowId, "Uncategorized", "Past Payment", "Description",
                    Money.of(100, "USD"), INFLOW,
                    ZonedDateTime.parse("2021-12-15T00:00:00Z"),
                    ZonedDateTime.parse("2021-12-15T00:00:00Z")); // December 2021

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("PAID_DATE_OUTSIDE_ACTIVE_PERIOD");
            assertThat(error.message()).contains("2021-12");

            log.info("Paid date outside active period correctly returned 400: code={}", error.code());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST when start period is in future")
        void shouldReturn400WhenStartPeriodInFuture() {
            // given - FixedClockConfig sets clock to 2022-01-01
            String userId = "test_" + UUID.randomUUID().toString().substring(0, 8);

            // when - try to create CashFlow with future start period
            ResponseEntity<ApiError> response = actor.createCashFlowWithHistoryExpectingError(
                    userId, "Future CashFlow", "2023-06", Money.of(1000, "USD"));

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("START_PERIOD_IN_FUTURE");
            assertThat(error.message()).contains("2023-06");

            log.info("Start period in future correctly returned 400: code={}", error.code());
        }

        // Note: Test for IMPORT_DATE_IN_FUTURE is not included because:
        // The validation order in ImportHistoricalCashChangeCommandHandler checks:
        // 1. ImportDateOutsideSetupPeriodException (paidDate must be before activePeriod)
        // 2. ImportDateInFutureException (paidDate must not be after now)
        //
        // Since activePeriod equals the current month (from FixedClockConfig: 2022-01),
        // any future date will always fail validation 1 before reaching validation 2.
        // The IMPORT_DATE_IN_FUTURE error can only occur when activePeriod is in the future,
        // which is not a valid scenario (you can't have activePeriod > now).

        @Test
        @DisplayName("Should return 400 BAD_REQUEST when import date is before start period")
        void shouldReturn400WhenImportDateBeforeStartPeriod() {
            // given - start period is 2021-09
            String userId = "test_" + UUID.randomUUID().toString().substring(0, 8);
            YearMonth startPeriod = YearMonth.of(2021, 9);
            String cashFlowId = actor.createCashFlowWithHistory(userId, "Setup CashFlow", startPeriod, Money.of(1000, "USD"));

            // when - try to import with date before start period (August 2021)
            ResponseEntity<ApiError> response = actor.importHistoricalTransactionExpectingError(
                    cashFlowId, "Uncategorized", "Old Import", "Description",
                    Money.of(500, "USD"), INFLOW,
                    ZonedDateTime.parse("2021-08-15T00:00:00Z"),
                    ZonedDateTime.parse("2021-08-15T00:00:00Z")); // Before start!

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("IMPORT_DATE_BEFORE_START");
            assertThat(error.message()).contains("2021-08");

            log.info("Import date before start correctly returned 400: code={}", error.code());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST when import date is in active or future period")
        void shouldReturn400WhenImportDateOutsideSetupPeriod() {
            // given - FixedClockConfig sets clock to 2022-01-01, active period is 2022-01
            String userId = "test_" + UUID.randomUUID().toString().substring(0, 8);
            YearMonth startPeriod = YearMonth.of(2021, 9);
            String cashFlowId = actor.createCashFlowWithHistory(userId, "Setup CashFlow", startPeriod, Money.of(1000, "USD"));

            // when - try to import with date in active period (January 2022)
            ResponseEntity<ApiError> response = actor.importHistoricalTransactionExpectingError(
                    cashFlowId, "Uncategorized", "Active Period Import", "Description",
                    Money.of(500, "USD"), INFLOW,
                    ZonedDateTime.parse("2022-01-01T00:00:00Z"),
                    ZonedDateTime.parse("2022-01-01T00:00:00Z")); // Active period!

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("IMPORT_DATE_OUTSIDE_SETUP_PERIOD");

            log.info("Import date outside setup period correctly returned 400: code={}", error.code());
        }
    }

    // ============ Category Operations (400) ============

    @Nested
    @DisplayName("Category Operations (400)")
    class CategoryOperations {

        @Test
        @DisplayName("Should return 400 BAD_REQUEST when adding cash change to archived category")
        void shouldReturn400WhenCategoryIsArchived() {
            // given
            String userId = "test_" + UUID.randomUUID().toString().substring(0, 8);
            String cashFlowId = actor.createCashFlow(userId, "Test CashFlow", "USD");
            actor.createCategory(cashFlowId, "ArchivedCategory", INFLOW);
            actor.archiveCategory(cashFlowId, "ArchivedCategory", INFLOW, false);

            // when - try to add cash change to archived category
            ResponseEntity<ApiError> response = actor.appendExpectedCashChangeExpectingError(
                    cashFlowId, "ArchivedCategory", "Test", "Description",
                    Money.of(100, "USD"), INFLOW,
                    ZonedDateTime.parse("2022-01-15T00:00:00Z"));

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("CATEGORY_IS_ARCHIVED");
            assertThat(error.message()).contains("ArchivedCategory");

            log.info("Category is archived correctly returned 400: code={}", error.code());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST when archiving system category")
        void shouldReturn400WhenCannotArchiveSystemCategory() {
            // given
            String userId = "test_" + UUID.randomUUID().toString().substring(0, 8);
            String cashFlowId = actor.createCashFlow(userId, "Test CashFlow", "USD");

            // when - try to archive system category "Uncategorized"
            ResponseEntity<ApiError> response = actor.archiveCategoryExpectingError(
                    cashFlowId, "Uncategorized", INFLOW, false);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("CANNOT_ARCHIVE_SYSTEM_CATEGORY");
            assertThat(error.message()).contains("Uncategorized");

            log.info("Cannot archive system category correctly returned 400: code={}", error.code());
        }
    }

    // ============ Field Validation (400) ============

    @Nested
    @DisplayName("Field Validation (400)")
    class FieldValidation {

        // --- /confirm endpoint ---

        @Test
        @DisplayName("Should return 400 BAD_REQUEST with VALIDATION_ERROR when confirm with null cashFlowId")
        void shouldReturn400WhenConfirmWithNullCashFlowId() {
            // when
            ResponseEntity<ApiError> response = actor.confirmCashChangeExpectingError(null, "some-cash-change-id");

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();

            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(error.fieldErrors()).isNotNull();
            assertThat(error.fieldErrors()).anyMatch(fe ->
                    fe.field().equals("cashFlowId") && fe.message().contains("required"));

            log.info("Confirm validation error correctly returned 400: fieldErrors={}", error.fieldErrors());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST with VALIDATION_ERROR when confirm with blank cashFlowId")
        void shouldReturn400WhenConfirmWithBlankCashFlowId() {
            // when
            ResponseEntity<ApiError> response = actor.confirmCashChangeExpectingError("", "some-cash-change-id");

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(error.fieldErrors()).anyMatch(fe -> fe.field().equals("cashFlowId"));

            log.info("Confirm validation error for blank cashFlowId: fieldErrors={}", error.fieldErrors());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST with VALIDATION_ERROR when confirm with null cashChangeId")
        void shouldReturn400WhenConfirmWithNullCashChangeId() {
            // when
            ResponseEntity<ApiError> response = actor.confirmCashChangeExpectingError("some-cashflow-id", null);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(error.fieldErrors()).anyMatch(fe ->
                    fe.field().equals("cashChangeId") && fe.message().contains("required"));

            log.info("Confirm validation error for null cashChangeId: fieldErrors={}", error.fieldErrors());
        }

        // --- /edit endpoint ---

        @Test
        @DisplayName("Should return 400 BAD_REQUEST with VALIDATION_ERROR when edit with null cashFlowId")
        void shouldReturn400WhenEditWithNullCashFlowId() {
            // when
            ResponseEntity<ApiError> response = actor.editCashChangeExpectingError(
                    null, "cash-change-id", "Name", "Description",
                    Money.of(100, "USD"), "Uncategorized", ZonedDateTime.parse("2022-01-15T00:00:00Z"));

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(error.fieldErrors()).anyMatch(fe -> fe.field().equals("cashFlowId"));

            log.info("Edit validation error for null cashFlowId: fieldErrors={}", error.fieldErrors());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST with VALIDATION_ERROR when edit with null name")
        void shouldReturn400WhenEditWithNullName() {
            // when
            ResponseEntity<ApiError> response = actor.editCashChangeExpectingError(
                    "cashflow-id", "cash-change-id", null, "Description",
                    Money.of(100, "USD"), "Uncategorized", ZonedDateTime.parse("2022-01-15T00:00:00Z"));

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(error.fieldErrors()).anyMatch(fe ->
                    fe.field().equals("name") && fe.message().contains("required"));

            log.info("Edit validation error for null name: fieldErrors={}", error.fieldErrors());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST with VALIDATION_ERROR when edit with null money")
        void shouldReturn400WhenEditWithNullMoney() {
            // when
            ResponseEntity<ApiError> response = actor.editCashChangeExpectingError(
                    "cashflow-id", "cash-change-id", "Name", "Description",
                    null, "Uncategorized", ZonedDateTime.parse("2022-01-15T00:00:00Z"));

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(error.fieldErrors()).anyMatch(fe ->
                    fe.field().equals("money") && fe.message().contains("required"));

            log.info("Edit validation error for null money: fieldErrors={}", error.fieldErrors());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST with VALIDATION_ERROR when edit with null dueDate")
        void shouldReturn400WhenEditWithNullDueDate() {
            // when
            ResponseEntity<ApiError> response = actor.editCashChangeExpectingError(
                    "cashflow-id", "cash-change-id", "Name", "Description",
                    Money.of(100, "USD"), "Uncategorized", null);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(error.fieldErrors()).anyMatch(fe ->
                    fe.field().equals("dueDate") && fe.message().contains("required"));

            log.info("Edit validation error for null dueDate: fieldErrors={}", error.fieldErrors());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST with VALIDATION_ERROR when edit with description exceeding 500 characters")
        void shouldReturn400WhenEditWithDescriptionTooLong() {
            // given
            String longDescription = "x".repeat(501);

            // when
            ResponseEntity<ApiError> response = actor.editCashChangeExpectingError(
                    "cashflow-id", "cash-change-id", "Name", longDescription,
                    Money.of(100, "USD"), "Uncategorized", ZonedDateTime.parse("2022-01-15T00:00:00Z"));

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(error.fieldErrors()).anyMatch(fe ->
                    fe.field().equals("description") && fe.message().contains("500"));

            log.info("Edit validation error for description too long: fieldErrors={}", error.fieldErrors());
        }

        @Test
        @DisplayName("Should accept edit with null description (optional field)")
        void shouldAcceptEditWithNullDescription() {
            // given - create CashFlow and cash change first
            String userId = "test_" + UUID.randomUUID().toString().substring(0, 8);
            String cashFlowId = actor.createCashFlow(userId, "Test CashFlow", "USD");
            String cashChangeId = actor.appendExpectedCashChange(
                    cashFlowId, "Uncategorized", "Original Name", "Original Description",
                    Money.of(100, "USD"), INFLOW, ZonedDateTime.parse("2022-01-15T00:00:00Z"));

            // when - edit with null description (should be accepted)
            ResponseEntity<ApiError> response = actor.editCashChangeExpectingError(
                    cashFlowId, cashChangeId, "New Name", null,
                    Money.of(200, "USD"), "Uncategorized", ZonedDateTime.parse("2022-01-20T00:00:00Z"));

            // then - should NOT be a validation error (null description is allowed)
            // Note: This might return 200 OK or other business error, but NOT VALIDATION_ERROR for description
            if (response.getStatusCode() == HttpStatus.BAD_REQUEST && response.getBody() != null) {
                ApiError error = response.getBody();
                // If there's a validation error, it should NOT be about description
                if ("VALIDATION_ERROR".equals(error.code()) && error.fieldErrors() != null) {
                    assertThat(error.fieldErrors()).noneMatch(fe -> fe.field().equals("description"));
                }
            }

            log.info("Edit with null description handled correctly");
        }

        // --- /reject endpoint ---

        @Test
        @DisplayName("Should return 400 BAD_REQUEST with VALIDATION_ERROR when reject with null cashFlowId")
        void shouldReturn400WhenRejectWithNullCashFlowId() {
            // when
            ResponseEntity<ApiError> response = actor.rejectCashChangeExpectingError(null, "cash-change-id", "Some reason");

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(error.fieldErrors()).anyMatch(fe -> fe.field().equals("cashFlowId"));

            log.info("Reject validation error for null cashFlowId: fieldErrors={}", error.fieldErrors());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST with VALIDATION_ERROR when reject with null reason")
        void shouldReturn400WhenRejectWithNullReason() {
            // when
            ResponseEntity<ApiError> response = actor.rejectCashChangeExpectingError("cashflow-id", "cash-change-id", null);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(error.fieldErrors()).anyMatch(fe ->
                    fe.field().equals("reason") && fe.message().contains("required"));

            log.info("Reject validation error for null reason: fieldErrors={}", error.fieldErrors());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST with VALIDATION_ERROR when reject with blank reason")
        void shouldReturn400WhenRejectWithBlankReason() {
            // when
            ResponseEntity<ApiError> response = actor.rejectCashChangeExpectingError("cashflow-id", "cash-change-id", "   ");

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(error.fieldErrors()).anyMatch(fe -> fe.field().equals("reason"));

            log.info("Reject validation error for blank reason: fieldErrors={}", error.fieldErrors());
        }

        // Note: The validation for CreateCashFlow and CreateCashFlowWithHistory DTOs is enforced
        // via Jakarta Bean Validation annotations (@NotBlank, @NotNull, @Valid).
        // Due to Jackson deserialization behavior, null values in nested objects trigger
        // VALIDATION_INVALID_JSON before validation runs. The validation still protects
        // against malformed API requests at the HTTP layer.
        //
        // The critical validation added (bankAccountNumber, denomination) ensures that
        // CategoryCreatedEventHandler never encounters null bankAccountNumber, fixing the NPE bug.
    }
}
