package com.multi.vidulum.cashflow.app;

import com.multi.vidulum.AbstractHttpIntegrationTest;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.error.ApiError;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.multi.vidulum.TestIds;

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
class CashFlowErrorHandlingTest extends AbstractHttpIntegrationTest {

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
            String userId = TestIds.nextUserId().getId();
            String cashFlowName = "Balance Mismatch Test";
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
            String userId = TestIds.nextUserId().getId();
            String cashFlowName = "Attestation Test Flow";
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
            String nonExistentCashFlowId = TestIds.nonExistentCashFlowId();

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
            String userId = TestIds.nextUserId().getId();
            String cashFlowId = actor.createCashFlow(userId, "Test CashFlow", "USD");
            String nonExistentCashChangeId = TestIds.nonExistentCashChangeId();

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
            String userId = TestIds.nextUserId().getId();
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
            String userId = TestIds.nextUserId().getId();
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
        @DisplayName("Should return 409 CONFLICT with CASHFLOW_NAME_ALREADY_EXISTS when creating CashFlow with duplicate name")
        void shouldReturn409WhenCashFlowNameAlreadyExists() {
            // given - create first CashFlow
            String userId = TestIds.nextUserId().getId();
            String duplicateName = "Duplicate Budget Name";
            actor.createCashFlow(userId, duplicateName, "USD");

            // when - try to create another CashFlow with the same name for the same user
            ResponseEntity<ApiError> response = actor.createCashFlowExpectingError(userId, duplicateName, "USD");

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();

            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(409);
            assertThat(error.code()).isEqualTo("CASHFLOW_NAME_ALREADY_EXISTS");
            assertThat(error.message()).contains(duplicateName);
            assertThat(error.message()).contains(userId);
            assertThat(error.fieldErrors()).isNull();
            assertThat(error.timestamp()).isNotNull();

            log.info("CashFlow name already exists correctly returned 409: code={}", error.code());
        }

        @Test
        @DisplayName("Should return 409 CONFLICT with CASHFLOW_NAME_ALREADY_EXISTS when creating CashFlow with history with duplicate name")
        void shouldReturn409WhenCashFlowWithHistoryNameAlreadyExists() {
            // given - create first CashFlow with history
            String userId = TestIds.nextUserId().getId();
            String duplicateName = "Duplicate History Budget";
            YearMonth startPeriod = YearMonth.of(2021, 9);
            Money initialBalance = Money.of(1000, "PLN");

            actor.createCashFlowWithHistory(userId, duplicateName, startPeriod, initialBalance);

            // when - try to create another CashFlow with history with the same name for the same user
            ResponseEntity<ApiError> response = actor.createCashFlowWithHistoryExpectingError(
                    userId, duplicateName, startPeriod.toString(), initialBalance);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();

            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(409);
            assertThat(error.code()).isEqualTo("CASHFLOW_NAME_ALREADY_EXISTS");
            assertThat(error.message()).contains(duplicateName);
            assertThat(error.message()).contains(userId);
            assertThat(error.fieldErrors()).isNull();

            log.info("CashFlow with history name already exists correctly returned 409: code={}", error.code());
        }

        @Test
        @DisplayName("Should return 409 CONFLICT when standard CashFlow exists and creating CashFlow with history with same name")
        void shouldReturn409WhenMixingCashFlowTypes() {
            // given - create standard CashFlow
            String userId = TestIds.nextUserId().getId();
            String duplicateName = "Mixed Type Budget";
            actor.createCashFlow(userId, duplicateName, "EUR");

            // when - try to create CashFlow with history with the same name
            YearMonth startPeriod = YearMonth.of(2021, 10);
            ResponseEntity<ApiError> response = actor.createCashFlowWithHistoryExpectingError(
                    userId, duplicateName, startPeriod.toString(), Money.of(500, "EUR"));

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();

            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(409);
            assertThat(error.code()).isEqualTo("CASHFLOW_NAME_ALREADY_EXISTS");
            assertThat(error.message()).contains(duplicateName);

            log.info("Mixed type CashFlow name conflict correctly returned 409: code={}", error.code());
        }

        @Test
        @DisplayName("Should allow same CashFlow name for different users")
        void shouldAllowSameCashFlowNameForDifferentUsers() {
            // given - create CashFlow for first user
            String userId1 = TestIds.nextUserId().getId();
            String userId2 = TestIds.nextUserId().getId();
            String sameName = "Family Budget";

            actor.createCashFlow(userId1, sameName, "USD");

            // when - create CashFlow with same name for different user
            String cashFlowId = actor.createCashFlow(userId2, sameName, "USD");

            // then - should succeed (different users can have same CashFlow name)
            assertThat(cashFlowId).isNotNull().isNotEmpty();

            log.info("Same CashFlow name allowed for different users: user1={}, user2={}, name={}",
                    userId1, userId2, sameName);
        }

        @Test
        @DisplayName("Should return 409 CONFLICT with CATEGORY_ALREADY_EXISTS when creating duplicate category")
        void shouldReturn409WhenCategoryAlreadyExists() {
            // given
            String userId = TestIds.nextUserId().getId();
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
            String userId = TestIds.nextUserId().getId();
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
            String userId = TestIds.nextUserId().getId();
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
            String userId = TestIds.nextUserId().getId();
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
            String userId = TestIds.nextUserId().getId();
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
            String userId = TestIds.nextUserId().getId();
            YearMonth startPeriod = YearMonth.of(2021, 9);
            String cashFlowId = actor.createCashFlowWithHistory(userId, "Setup CashFlow", startPeriod, Money.of(1000, "USD"));

            // when
            ResponseEntity<ApiError> response = actor.editCashChangeExpectingError(
                    cashFlowId, TestIds.nonExistentCashChangeId(), "New Name", "New Description",
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
            String userId = TestIds.nextUserId().getId();
            YearMonth startPeriod = YearMonth.of(2021, 9);
            String cashFlowId = actor.createCashFlowWithHistory(userId, "Setup CashFlow", startPeriod, Money.of(1000, "USD"));

            // when
            ResponseEntity<ApiError> response = actor.confirmCashChangeExpectingError(cashFlowId, TestIds.nonExistentCashChangeId());

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("CASHFLOW_OPERATION_NOT_ALLOWED_IN_SETUP");

            log.info("Confirm not allowed in SETUP mode correctly returned 400: code={}", error.code());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST when importing to FORECASTED month in OPEN mode")
        void shouldReturn400WhenImportToForecastedMonth() {
            // given - create standard CashFlow (OPEN mode, activePeriod = 2022-01)
            String userId = TestIds.nextUserId().getId();
            String cashFlowId = actor.createCashFlow(userId, "Open CashFlow", "USD");

            // when - try to import to a FORECASTED month (2022-02 is after activePeriod 2022-01)
            ResponseEntity<ApiError> response = actor.importHistoricalTransactionExpectingError(
                    cashFlowId, "Uncategorized", "Future Import", "Desc",
                    Money.of(500, "USD"), INFLOW,
                    ZonedDateTime.parse("2022-02-15T00:00:00Z"),
                    ZonedDateTime.parse("2022-02-15T00:00:00Z"));

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("IMPORT_TO_FORECASTED_MONTH_NOT_ALLOWED");
            assertThat(error.message()).contains("FORECASTED");

            log.info("Import to FORECASTED month correctly returned 400: code={}", error.code());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST when attesting non-SETUP mode CashFlow")
        void shouldReturn400WhenAttestationNotAllowedInNonSetupMode() {
            // given - create standard CashFlow (OPEN mode)
            String userId = TestIds.nextUserId().getId();
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
            String userId = TestIds.nextUserId().getId();
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
            String userId = TestIds.nextUserId().getId();
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
            String userId = TestIds.nextUserId().getId();
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
            String userId = TestIds.nextUserId().getId();
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
            String userId = TestIds.nextUserId().getId();

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
            String userId = TestIds.nextUserId().getId();
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
            String userId = TestIds.nextUserId().getId();
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

        @Test
        @DisplayName("Should return 400 BAD_REQUEST when dueDate is before activePeriod on append")
        void shouldReturn400WhenDueDateBeforeActivePeriodOnAppend() {
            // given - FixedClockConfig sets clock to 2022-01-01, active period is 2022-01
            String userId = TestIds.nextUserId().getId();
            String cashFlowId = actor.createCashFlow(userId, "Test CashFlow", "USD");

            // when - try to append expected cash change with dueDate in December 2021 (before active period)
            ResponseEntity<ApiError> response = actor.appendExpectedCashChangeExpectingError(
                    cashFlowId, "Uncategorized", "Past Month Payment", "Description",
                    Money.of(100, "USD"), INFLOW,
                    ZonedDateTime.parse("2021-12-15T00:00:00Z")); // December 2021 - before active period!

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("DUE_DATE_OUTSIDE_ALLOWED_RANGE");
            assertThat(error.message()).contains("2021-12");

            log.info("DueDate before activePeriod correctly returned 400: code={}", error.code());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST when dueDate is more than 11 months ahead on append")
        void shouldReturn400WhenDueDateTooFarInFutureOnAppend() {
            // given - FixedClockConfig sets clock to 2022-01-01, active period is 2022-01
            // Allowed range: 2022-01 to 2022-12 (activePeriod + 11 months)
            String userId = TestIds.nextUserId().getId();
            String cashFlowId = actor.createCashFlow(userId, "Test CashFlow", "USD");

            // when - try to append expected cash change with dueDate in January 2023 (> 11 months ahead)
            ResponseEntity<ApiError> response = actor.appendExpectedCashChangeExpectingError(
                    cashFlowId, "Uncategorized", "Far Future Payment", "Description",
                    Money.of(100, "USD"), INFLOW,
                    ZonedDateTime.parse("2023-01-15T00:00:00Z")); // January 2023 - too far!

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("DUE_DATE_OUTSIDE_ALLOWED_RANGE");
            assertThat(error.message()).contains("2023-01");

            log.info("DueDate too far in future correctly returned 400: code={}", error.code());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST when dueDate is before activePeriod on edit")
        void shouldReturn400WhenDueDateBeforeActivePeriodOnEdit() {
            // given - FixedClockConfig sets clock to 2022-01-01, active period is 2022-01
            String userId = TestIds.nextUserId().getId();
            String cashFlowId = actor.createCashFlow(userId, "Test CashFlow", "USD");

            // Create a valid cash change first
            String cashChangeId = actor.appendExpectedCashChange(
                    cashFlowId, "Uncategorized", "Test Payment", "Description",
                    Money.of(100, "USD"), INFLOW, ZonedDateTime.parse("2022-01-15T00:00:00Z"));

            // when - try to edit with dueDate in December 2021 (before active period)
            ResponseEntity<ApiError> response = actor.editCashChangeExpectingError(
                    cashFlowId, cashChangeId, "Updated Payment", "Updated Description",
                    Money.of(150, "USD"), "Uncategorized",
                    ZonedDateTime.parse("2021-12-15T00:00:00Z")); // December 2021 - before active period!

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("DUE_DATE_OUTSIDE_ALLOWED_RANGE");
            assertThat(error.message()).contains("2021-12");

            log.info("Edit with dueDate before activePeriod correctly returned 400: code={}", error.code());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST when dueDate is more than 11 months ahead on edit")
        void shouldReturn400WhenDueDateTooFarInFutureOnEdit() {
            // given - FixedClockConfig sets clock to 2022-01-01, active period is 2022-01
            String userId = TestIds.nextUserId().getId();
            String cashFlowId = actor.createCashFlow(userId, "Test CashFlow", "USD");

            // Create a valid cash change first
            String cashChangeId = actor.appendExpectedCashChange(
                    cashFlowId, "Uncategorized", "Test Payment", "Description",
                    Money.of(100, "USD"), INFLOW, ZonedDateTime.parse("2022-01-15T00:00:00Z"));

            // when - try to edit with dueDate in January 2023 (> 11 months ahead)
            ResponseEntity<ApiError> response = actor.editCashChangeExpectingError(
                    cashFlowId, cashChangeId, "Updated Payment", "Updated Description",
                    Money.of(150, "USD"), "Uncategorized",
                    ZonedDateTime.parse("2023-01-15T00:00:00Z")); // January 2023 - too far!

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("DUE_DATE_OUTSIDE_ALLOWED_RANGE");
            assertThat(error.message()).contains("2023-01");

            log.info("Edit with dueDate too far in future correctly returned 400: code={}", error.code());
        }

        @Test
        @DisplayName("Should accept dueDate at max allowed month boundary (activePeriod + 11 months)")
        void shouldAcceptDueDateAtMaxBoundary() {
            // given - FixedClockConfig sets clock to 2022-01-01, active period is 2022-01
            // Max allowed month: 2022-12 (activePeriod + 11 months)
            String userId = TestIds.nextUserId().getId();
            String cashFlowId = actor.createCashFlow(userId, "Test CashFlow", "USD");

            // when - append with dueDate in December 2022 (exactly at boundary)
            String cashChangeId = actor.appendExpectedCashChange(
                    cashFlowId, "Uncategorized", "Boundary Payment", "At max allowed month",
                    Money.of(100, "USD"), INFLOW,
                    ZonedDateTime.parse("2022-12-31T23:59:59Z")); // December 2022 - exactly at boundary

            // then - should succeed
            assertThat(cashChangeId).isNotNull().isNotEmpty();

            log.info("DueDate at max boundary accepted: cashChangeId={}", cashChangeId);
        }
    }

    // ============ Edit Operations ============

    @Nested
    @DisplayName("Edit Operations")
    class EditOperations {

        @Test
        @DisplayName("Should successfully edit cash change to different category")
        void shouldEditCashChangeToDifferentCategory() {
            // given - create CashFlow with two categories
            String userId = TestIds.nextUserId().getId();
            String cashFlowId = actor.createCashFlow(userId, "Test CashFlow", "USD");
            actor.createCategory(cashFlowId, "Salary", INFLOW);
            actor.createCategory(cashFlowId, "Bonus", INFLOW);

            // Create cash change in "Salary" category
            String cashChangeId = actor.appendExpectedCashChange(
                    cashFlowId, "Salary", "Monthly Salary", "Regular payment",
                    Money.of(5000, "USD"), INFLOW, ZonedDateTime.parse("2022-01-15T00:00:00Z"));

            // when - edit to move to "Bonus" category
            actor.editCashChange(
                    cashFlowId, cashChangeId, "Year-end Bonus", "Performance bonus",
                    Money.of(2000, "USD"), "Bonus", ZonedDateTime.parse("2022-01-20T00:00:00Z"));

            // then - verify the change
            CashFlowDto.CashFlowSummaryJson result = actor.getCashFlow(cashFlowId);
            CashFlowDto.CashChangeSummaryJson editedCashChange = result.getCashChanges().get(cashChangeId);

            assertThat(editedCashChange).isNotNull();
            assertThat(editedCashChange.getCategoryName()).isEqualTo("Bonus");
            assertThat(editedCashChange.getName()).isEqualTo("Year-end Bonus");
            assertThat(editedCashChange.getMoney()).isEqualTo(Money.of(2000, "USD"));

            log.info("Successfully edited cash change to different category: {} -> Bonus", cashChangeId);
        }

        @Test
        @DisplayName("Should successfully edit cash change to different month within allowed range")
        void shouldEditCashChangeToDifferentMonth() {
            // given - FixedClockConfig sets clock to 2022-01-01, active period is 2022-01
            // Allowed range: 2022-01 to 2022-12
            String userId = TestIds.nextUserId().getId();
            String cashFlowId = actor.createCashFlow(userId, "Test CashFlow", "USD");

            // Create cash change in January
            String cashChangeId = actor.appendExpectedCashChange(
                    cashFlowId, "Uncategorized", "Payment", "Description",
                    Money.of(100, "USD"), INFLOW, ZonedDateTime.parse("2022-01-15T00:00:00Z"));

            // when - edit to move to June (within allowed range)
            actor.editCashChange(
                    cashFlowId, cashChangeId, "Payment - Rescheduled", "Moved to June",
                    Money.of(100, "USD"), "Uncategorized", ZonedDateTime.parse("2022-06-20T00:00:00Z"));

            // then - verify the change
            CashFlowDto.CashFlowSummaryJson result = actor.getCashFlow(cashFlowId);
            CashFlowDto.CashChangeSummaryJson editedCashChange = result.getCashChanges().get(cashChangeId);

            assertThat(editedCashChange).isNotNull();
            assertThat(editedCashChange.getDueDate()).isEqualTo(ZonedDateTime.parse("2022-06-20T00:00:00Z"));
            assertThat(editedCashChange.getName()).isEqualTo("Payment - Rescheduled");

            log.info("Successfully edited cash change to different month: {} -> 2022-06", cashChangeId);
        }

        @Test
        @DisplayName("Should successfully edit cash change to last allowed month (activePeriod + 11)")
        void shouldEditCashChangeToLastAllowedMonth() {
            // given - FixedClockConfig sets clock to 2022-01-01, max allowed is 2022-12
            String userId = TestIds.nextUserId().getId();
            String cashFlowId = actor.createCashFlow(userId, "Test CashFlow", "USD");

            String cashChangeId = actor.appendExpectedCashChange(
                    cashFlowId, "Uncategorized", "Payment", "Description",
                    Money.of(100, "USD"), INFLOW, ZonedDateTime.parse("2022-01-15T00:00:00Z"));

            // when - edit to December 2022 (boundary)
            actor.editCashChange(
                    cashFlowId, cashChangeId, "December Payment", "End of year",
                    Money.of(150, "USD"), "Uncategorized", ZonedDateTime.parse("2022-12-25T00:00:00Z"));

            // then
            CashFlowDto.CashFlowSummaryJson result = actor.getCashFlow(cashFlowId);
            CashFlowDto.CashChangeSummaryJson editedCashChange = result.getCashChanges().get(cashChangeId);

            assertThat(editedCashChange).isNotNull();
            assertThat(editedCashChange.getDueDate()).isEqualTo(ZonedDateTime.parse("2022-12-25T00:00:00Z"));

            log.info("Successfully edited cash change to last allowed month: {} -> 2022-12", cashChangeId);
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST when editing cash change to archived category")
        void shouldReturn400WhenEditingToArchivedCategory() {
            // given - create CashFlow with one active and one archived category
            String userId = TestIds.nextUserId().getId();
            String cashFlowId = actor.createCashFlow(userId, "Test CashFlow", "USD");
            actor.createCategory(cashFlowId, "ActiveCategory", INFLOW);
            actor.createCategory(cashFlowId, "ArchivedCategory", INFLOW);
            actor.archiveCategory(cashFlowId, "ArchivedCategory", INFLOW, false);

            // Create cash change in active category
            String cashChangeId = actor.appendExpectedCashChange(
                    cashFlowId, "ActiveCategory", "Payment", "Description",
                    Money.of(100, "USD"), INFLOW, ZonedDateTime.parse("2022-01-15T00:00:00Z"));

            // when - try to edit to move to archived category
            ResponseEntity<ApiError> response = actor.editCashChangeExpectingError(
                    cashFlowId, cashChangeId, "Payment", "Description",
                    Money.of(100, "USD"), "ArchivedCategory", ZonedDateTime.parse("2022-01-15T00:00:00Z"));

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("CATEGORY_IS_ARCHIVED");
            assertThat(error.message()).contains("ArchivedCategory");

            log.info("Edit to archived category correctly returned 400: code={}", error.code());
        }

        @Test
        @DisplayName("Should return 404 NOT_FOUND when editing cash change to non-existent category")
        void shouldReturn404WhenEditingToNonExistentCategory() {
            // given
            String userId = TestIds.nextUserId().getId();
            String cashFlowId = actor.createCashFlow(userId, "Test CashFlow", "USD");

            String cashChangeId = actor.appendExpectedCashChange(
                    cashFlowId, "Uncategorized", "Payment", "Description",
                    Money.of(100, "USD"), INFLOW, ZonedDateTime.parse("2022-01-15T00:00:00Z"));

            // when - try to edit to non-existent category
            ResponseEntity<ApiError> response = actor.editCashChangeExpectingError(
                    cashFlowId, cashChangeId, "Payment", "Description",
                    Money.of(100, "USD"), "NonExistentCategory", ZonedDateTime.parse("2022-01-15T00:00:00Z"));

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(404);
            assertThat(error.code()).isEqualTo("CATEGORY_NOT_FOUND");
            assertThat(error.message()).contains("NonExistentCategory");

            log.info("Edit to non-existent category correctly returned 404: code={}", error.code());
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
            String userId = TestIds.nextUserId().getId();
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
            String userId = TestIds.nextUserId().getId();
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

        // --- CashFlow name validation ---

        @Test
        @DisplayName("Should return 400 VALIDATION_ERROR when CashFlow name is too short (less than 5 characters)")
        void shouldReturn400WhenCashFlowNameTooShort() {
            // given
            String userId = TestIds.nextUserId().getId();
            String shortName = "Test"; // 4 characters - too short

            // when
            ResponseEntity<ApiError> response = actor.createCashFlowExpectingError(userId, shortName, "USD");

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();

            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(error.fieldErrors()).isNotNull();
            assertThat(error.fieldErrors()).anyMatch(fe ->
                    fe.field().equals("name") && fe.message().contains("5") && fe.message().contains("30"));

            log.info("CashFlow name too short correctly returned 400: code={}, fieldErrors={}",
                    error.code(), error.fieldErrors());
        }

        @Test
        @DisplayName("Should return 400 VALIDATION_ERROR when CashFlow name is too long (more than 30 characters)")
        void shouldReturn400WhenCashFlowNameTooLong() {
            // given
            String userId = TestIds.nextUserId().getId();
            String longName = "A".repeat(31); // 31 characters - too long

            // when
            ResponseEntity<ApiError> response = actor.createCashFlowExpectingError(userId, longName, "USD");

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();

            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(error.fieldErrors()).isNotNull();
            assertThat(error.fieldErrors()).anyMatch(fe ->
                    fe.field().equals("name") && fe.message().contains("5") && fe.message().contains("30"));

            log.info("CashFlow name too long correctly returned 400: code={}, fieldErrors={}",
                    error.code(), error.fieldErrors());
        }

        @Test
        @DisplayName("Should return 400 VALIDATION_ERROR when CashFlow with history name is too short")
        void shouldReturn400WhenCashFlowWithHistoryNameTooShort() {
            // given
            String userId = TestIds.nextUserId().getId();
            String shortName = "Ab"; // 2 characters - too short
            YearMonth startPeriod = YearMonth.of(2021, 9);
            Money initialBalance = Money.of(1000, "PLN");

            // when
            ResponseEntity<ApiError> response = actor.createCashFlowWithHistoryExpectingError(
                    userId, shortName, startPeriod.toString(), initialBalance);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();

            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(error.fieldErrors()).isNotNull();
            assertThat(error.fieldErrors()).anyMatch(fe ->
                    fe.field().equals("name") && fe.message().contains("5") && fe.message().contains("30"));

            log.info("CashFlow with history name too short correctly returned 400: code={}, fieldErrors={}",
                    error.code(), error.fieldErrors());
        }

        @Test
        @DisplayName("Should return 400 VALIDATION_ERROR when CashFlow with history name is too long")
        void shouldReturn400WhenCashFlowWithHistoryNameTooLong() {
            // given
            String userId = TestIds.nextUserId().getId();
            String longName = "Very Long CashFlow Name That Exceeds Limit"; // > 30 characters
            YearMonth startPeriod = YearMonth.of(2021, 9);
            Money initialBalance = Money.of(1000, "EUR");

            // when
            ResponseEntity<ApiError> response = actor.createCashFlowWithHistoryExpectingError(
                    userId, longName, startPeriod.toString(), initialBalance);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();

            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("VALIDATION_ERROR");
            assertThat(error.fieldErrors()).isNotNull();
            assertThat(error.fieldErrors()).anyMatch(fe ->
                    fe.field().equals("name") && fe.message().contains("5") && fe.message().contains("30"));

            log.info("CashFlow with history name too long correctly returned 400: code={}, fieldErrors={}",
                    error.code(), error.fieldErrors());
        }

        @Test
        @DisplayName("Should accept CashFlow name at minimum length (5 characters)")
        void shouldAcceptCashFlowNameAtMinLength() {
            // given
            String userId = TestIds.nextUserId().getId();
            String minLengthName = "Budge"; // Exactly 5 characters

            // when
            String cashFlowId = actor.createCashFlow(userId, minLengthName, "USD");

            // then - should succeed
            assertThat(cashFlowId).isNotNull().isNotEmpty();

            log.info("CashFlow name at min length accepted: name={}, cashFlowId={}", minLengthName, cashFlowId);
        }

        @Test
        @DisplayName("Should accept CashFlow name at maximum length (30 characters)")
        void shouldAcceptCashFlowNameAtMaxLength() {
            // given
            String userId = TestIds.nextUserId().getId();
            String maxLengthName = "A".repeat(30); // Exactly 30 characters

            // when
            String cashFlowId = actor.createCashFlow(userId, maxLengthName, "USD");

            // then - should succeed
            assertThat(cashFlowId).isNotNull().isNotEmpty();

            log.info("CashFlow name at max length accepted: length={}, cashFlowId={}", maxLengthName.length(), cashFlowId);
        }

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
            String userId = TestIds.nextUserId().getId();
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

    // ============ UserId Format Validation (400) ============

    @Nested
    @DisplayName("UserId Format Validation (400)")
    class UserIdFormatValidation {

        @Test
        @DisplayName("Should return 400 BAD_REQUEST with INVALID_USER_ID_FORMAT when userId has wrong format")
        void shouldReturn400WhenUserIdHasWrongFormat() {
            // given - invalid userId (not matching UXXXXXXXX pattern)
            String invalidUserId = "invalid-user-id";
            String cashFlowName = "Test CashFlow";
            YearMonth startPeriod = YearMonth.of(2022, 1);
            Money initialBalance = Money.of(1000, "USD");

            // when - try to create CashFlow with invalid userId
            ResponseEntity<ApiError> response = actor.createCashFlowWithHistoryExpectingError(
                    invalidUserId,
                    cashFlowName,
                    startPeriod.toString(),
                    initialBalance
            );

            // then - should return 400 BAD_REQUEST with INVALID_USER_ID_FORMAT error
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();

            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("INVALID_USER_ID_FORMAT");
            assertThat(error.message()).contains(invalidUserId);
            assertThat(error.message()).contains("UXXXXXXXX");
            assertThat(error.fieldErrors()).isNull();
            assertThat(error.timestamp()).isNotNull();

            log.info("Invalid userId correctly returned 400: code={}, message={}",
                    error.code(), error.message());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST when userId is UUID format (legacy)")
        void shouldReturn400WhenUserIdIsUuidFormat() {
            // given - UUID format userId (legacy format - no longer valid)
            String uuidUserId = UUID.randomUUID().toString();
            String cashFlowName = "Test CashFlow";
            YearMonth startPeriod = YearMonth.of(2022, 1);
            Money initialBalance = Money.of(1000, "PLN");

            // when - try to create CashFlow with UUID userId
            ResponseEntity<ApiError> response = actor.createCashFlowWithHistoryExpectingError(
                    uuidUserId,
                    cashFlowName,
                    startPeriod.toString(),
                    initialBalance
            );

            // then - should return 400 BAD_REQUEST with INVALID_USER_ID_FORMAT error
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();

            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("INVALID_USER_ID_FORMAT");
            assertThat(error.message()).contains(uuidUserId);
            assertThat(error.message()).contains("UXXXXXXXX");
            assertThat(error.fieldErrors()).isNull();

            log.info("UUID userId correctly rejected with 400: code={}", error.code());
        }

        @Test
        @DisplayName("Should accept valid userId format (UXXXXXXXX)")
        void shouldAcceptValidUserIdFormat() {
            // given - valid userId format
            String validUserId = TestIds.nextUserId().getId();
            String cashFlowName = "Test CashFlow";
            YearMonth startPeriod = YearMonth.of(2022, 1);
            Money initialBalance = Money.of(1000, "EUR");

            // when - create CashFlow with valid userId
            String cashFlowId = actor.createCashFlowWithHistory(
                    validUserId,
                    cashFlowName,
                    startPeriod,
                    initialBalance
            );

            // then - should succeed
            assertThat(cashFlowId).isNotNull().isNotEmpty();

            log.info("Valid userId accepted: userId={}, cashFlowId={}", validUserId, cashFlowId);
        }
    }

    // ============ IBAN/SWIFT Validation (400) ============

    @Nested
    @DisplayName("IBAN/SWIFT Validation (400)")
    class IbanSwiftValidation {

        @Test
        @DisplayName("Should return 400 BAD_REQUEST when IBAN has invalid format")
        void shouldReturn400WhenIbanFormatInvalid() {
            // given
            String userId = TestIds.nextUserId().getId();
            String invalidIban = "INVALID_IBAN";

            // when
            ResponseEntity<ApiError> response = actor.createCashFlowWithInvalidIban(
                    userId, "Test CashFlow", invalidIban, "USD");

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();

            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("INVALID_BANK_ACCOUNT");
            assertThat(error.message()).contains("Unsupported IBAN country code");
            assertThat(error.fieldErrors()).isNull();

            log.info("Invalid IBAN format correctly returned 400: code={}, message={}",
                    error.code(), error.message());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST when IBAN has invalid checksum")
        void shouldReturn400WhenIbanChecksumInvalid() {
            // given - valid format but wrong checksum (last digit changed)
            String userId = TestIds.nextUserId().getId();
            String invalidIban = "PL61109010140000071219812875"; // Wrong checksum

            // when
            ResponseEntity<ApiError> response = actor.createCashFlowWithInvalidIban(
                    userId, "Test CashFlow", invalidIban, "PLN");

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();

            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("INVALID_BANK_ACCOUNT");
            assertThat(error.message()).contains("check digits");

            log.info("Invalid IBAN checksum correctly returned 400: code={}", error.code());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST when IBAN is too short")
        void shouldReturn400WhenIbanTooShort() {
            // given
            String userId = TestIds.nextUserId().getId();
            String shortIban = "PL12";

            // when
            ResponseEntity<ApiError> response = actor.createCashFlowWithInvalidIban(
                    userId, "Test CashFlow", shortIban, "PLN");

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            ApiError error = response.getBody();
            assertThat(error.code()).isEqualTo("INVALID_BANK_ACCOUNT");
            assertThat(error.message()).contains("format");

            log.info("Too short IBAN correctly returned 400: code={}", error.code());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST when SWIFT/BIC is invalid")
        void shouldReturn400WhenSwiftBicInvalid() {
            // given
            String userId = TestIds.nextUserId().getId();
            String validIban = "DE89370400440532013000";
            String invalidBic = "INVALID";

            // when
            ResponseEntity<ApiError> response = actor.createCashFlowWithInvalidSwift(
                    userId, "Test CashFlow", validIban, invalidBic);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();

            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("INVALID_BANK_ACCOUNT");
            assertThat(error.message()).contains("BIC/SWIFT");
            assertThat(error.message()).contains("format");

            log.info("Invalid SWIFT/BIC correctly returned 400: code={}", error.code());
        }

        @Test
        @DisplayName("Should accept valid Polish IBAN")
        void shouldAcceptValidPolishIban() {
            // given
            String userId = TestIds.nextUserId().getId();
            String validIban = "PL61109010140000071219812874";

            // when
            String cashFlowId = actor.createCashFlowWithIban(userId, "Polish Budget", validIban, "PLN");

            // then
            assertThat(cashFlowId).isNotNull().isNotEmpty();

            log.info("Valid Polish IBAN accepted: cashFlowId={}", cashFlowId);
        }

        @Test
        @DisplayName("Should accept valid German IBAN")
        void shouldAcceptValidGermanIban() {
            // given
            String userId = TestIds.nextUserId().getId();
            String validIban = "DE89370400440532013000";

            // when
            String cashFlowId = actor.createCashFlowWithIban(userId, "German Budget", validIban, "EUR");

            // then
            assertThat(cashFlowId).isNotNull().isNotEmpty();

            log.info("Valid German IBAN accepted: cashFlowId={}", cashFlowId);
        }

        @Test
        @DisplayName("Should accept valid IBAN with spaces (normalization)")
        void shouldAcceptIbanWithSpaces() {
            // given
            String userId = TestIds.nextUserId().getId();
            String ibanWithSpaces = "PL 61 1090 1014 0000 0712 1981 2874";

            // when
            String cashFlowId = actor.createCashFlowWithIban(userId, "Spaced IBAN", ibanWithSpaces, "PLN");

            // then
            assertThat(cashFlowId).isNotNull().isNotEmpty();

            log.info("IBAN with spaces accepted (normalized): cashFlowId={}", cashFlowId);
        }

        @Test
        @DisplayName("Should accept valid IBAN with SWIFT/BIC")
        void shouldAcceptValidIbanWithSwift() {
            // given
            String userId = TestIds.nextUserId().getId();
            String validIban = "PL61109010140000071219812874";
            String validBic = "BPKOPLPW";

            // when
            String cashFlowId = actor.createCashFlowWithSwift(userId, "PKO BP Account", validIban, validBic);

            // then
            assertThat(cashFlowId).isNotNull().isNotEmpty();

            log.info("Valid IBAN with SWIFT/BIC accepted: cashFlowId={}", cashFlowId);
        }

        @Test
        @DisplayName("Should accept valid IBAN without SWIFT/BIC (optional)")
        void shouldAcceptValidIbanWithoutSwift() {
            // given
            String userId = TestIds.nextUserId().getId();
            String validIban = "GB29NWBK60161331926819";

            // when
            String cashFlowId = actor.createCashFlowWithIban(userId, "UK Account", validIban, "GBP");

            // then
            assertThat(cashFlowId).isNotNull().isNotEmpty();

            log.info("Valid IBAN without SWIFT accepted: cashFlowId={}", cashFlowId);
        }
    }

    // ============ Month Rollover Errors (400) ============

    @Nested
    @DisplayName("400 BAD_REQUEST - Month Rollover Errors")
    class MonthRolloverErrors {

        @Test
        @DisplayName("Should return 400 BAD_REQUEST with CASHFLOW_ROLLOVER_NOT_ALLOWED when CashFlow is in SETUP mode")
        void shouldReturn400WhenRolloverOnSetupModeCashFlow() {
            // given - create CashFlow with history (will be in SETUP mode)
            String userId = TestIds.nextUserId().getId();
            String cashFlowId = actor.createCashFlowWithHistory(
                    userId,
                    "Setup Mode CashFlow",
                    YearMonth.of(2021, 6),
                    Money.of(1000.0, "PLN")
            );

            // when - try to rollover while in SETUP mode
            ResponseEntity<ApiError> response = actor.rolloverMonthExpectingError(cashFlowId);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();

            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("CASHFLOW_ROLLOVER_NOT_ALLOWED");
            assertThat(error.message())
                    .as("Error message should explain that CashFlow must be in OPEN status")
                    .containsIgnoringCase("OPEN");
            assertThat(error.fieldErrors()).isNull();
            assertThat(error.timestamp()).isNotNull();

            log.info("Rollover on SETUP mode CashFlow: cashFlowId={}, message={}",
                    cashFlowId, error.message());
        }
    }
}
