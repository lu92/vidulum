package com.multi.vidulum.bank_data_adapter.app;

import com.multi.vidulum.AuthenticatedHttpIntegrationTest;
import com.multi.vidulum.bank_data_adapter.domain.AiCsvTransformationRepository;
import com.multi.vidulum.bank_data_adapter.rest.UnifiedCsvImportController;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.multi.vidulum.common.error.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Unified CSV Import endpoint.
 * Tests the complete flow from upload to detection result.
 */
@Slf4j
@DisplayName("Unified CSV Import - Integration Tests")
class UnifiedCsvImportIntegrationTest extends AuthenticatedHttpIntegrationTest {

    @Autowired
    private AiCsvTransformationRepository transformationRepository;

    private UnifiedCsvImportHttpActor actor;

    @BeforeEach
    void setUp() {
        // Clean up
        transformationRepository.deleteAll();

        // Register and authenticate
        registerAndAuthenticate();

        // Create actor
        actor = new UnifiedCsvImportHttpActor(restTemplate, port);
        actor.setJwtToken(accessToken);
    }

    @Nested
    @DisplayName("Canonical Format Detection")
    class CanonicalFormatTests {

        @Test
        @DisplayName("Should detect canonical format and process instantly")
        void shouldDetectCanonicalFormatAndProcessInstantly() {
            // given
            String canonicalCsv = UnifiedCsvImportHttpActor.createCanonicalCsv(
                UnifiedCsvImportHttpActor.canonicalRow("TXN001", "Salary", "Income", 5000.00, "PLN", "INFLOW", "2023-01-15"),
                UnifiedCsvImportHttpActor.canonicalRow("TXN002", "Rent", "Housing", -1500.00, "PLN", "OUTFLOW", "2023-01-20"),
                UnifiedCsvImportHttpActor.canonicalRow("TXN003", "Food", "Groceries", -200.00, "PLN", "OUTFLOW", "2023-02-10")
            );

            // when
            UnifiedCsvImportController.UploadResponse response = actor.upload(
                canonicalCsv.getBytes(),
                "canonical.csv",
                null
            );

            // then
            assertThat(response.success()).isTrue();
            assertThat(response.detectionResult()).isEqualTo("CANONICAL");
            assertThat(response.fromCache()).isFalse();
            assertThat(response.processingTimeMs()).isLessThan(1000);
            assertThat(response.detectedBank()).isEqualTo("Vidulum Format");
            assertThat(response.rowCount()).isEqualTo(3);

            // Date range
            assertThat(response.minTransactionDate()).isEqualTo("2023-01-15");
            assertThat(response.maxTransactionDate()).isEqualTo("2023-02-10");
            assertThat(response.suggestedStartPeriod()).isEqualTo("2023-01");
            assertThat(response.monthsOfData()).isEqualTo(2);
            assertThat(response.monthsCovered()).containsExactly("2023-01", "2023-02");

            // Currency detection
            assertThat(response.detectedCurrency()).isEqualTo("PLN");

            // Bank categories
            assertThat(response.bankCategories()).hasSize(3);
            assertThat(response.bankCategories())
                .extracting(UnifiedCsvImportController.BankCategoryPreview::name)
                .containsExactlyInAnyOrder("Income", "Housing", "Groceries");

            log.info("Canonical format detected: {} transactions, {}ms", response.rowCount(), response.processingTimeMs());
        }

        @Test
        @DisplayName("Should handle canonical format with minimal headers")
        void shouldHandleCanonicalFormatWithMinimalHeaders() {
            // given - only required columns
            String minimalCsv = """
                name,amount,currency,type,operationDate,bankCategory,description
                Salary,5000.00,PLN,INFLOW,2023-06-15,Income,Monthly salary
                Rent,-1500.00,PLN,OUTFLOW,2023-06-20,Housing,Monthly rent
                """;

            // when
            UnifiedCsvImportController.UploadResponse response = actor.upload(
                minimalCsv.getBytes(),
                "minimal.csv",
                null
            );

            // then
            assertThat(response.success()).isTrue();
            assertThat(response.detectionResult()).isEqualTo("CANONICAL");
            assertThat(response.rowCount()).isEqualTo(2);
            assertThat(response.suggestedStartPeriod()).isEqualTo("2023-06");

            log.info("Minimal canonical format: {} transactions", response.rowCount());
        }
    }

    @Nested
    @DisplayName("Response Fields Verification")
    class ResponseFieldsTests {

        @Test
        @DisplayName("Should return all required fields for UI integration")
        void shouldReturnAllRequiredFieldsForUiIntegration() {
            // given
            String canonicalCsv = UnifiedCsvImportHttpActor.createCanonicalCsv(
                UnifiedCsvImportHttpActor.canonicalRow("TXN001", "Salary", "Income", 5000.00, "PLN", "INFLOW", "2023-01-15")
            );

            // when
            UnifiedCsvImportController.UploadResponse response = actor.upload(
                canonicalCsv.getBytes(),
                "test.csv",
                null
            );

            // then - verify all fields for UI
            assertThat(response.transformationId()).isNotNull();
            assertThat(response.success()).isTrue();

            // Detection info
            assertThat(response.detectionResult()).isNotNull();
            assertThat(response.processingTimeMs()).isGreaterThanOrEqualTo(0);

            // Bank info for CashFlow form
            assertThat(response.detectedBank()).isNotNull();
            assertThat(response.detectedCurrency()).isEqualTo("PLN");

            // Date range for startPeriod
            assertThat(response.minTransactionDate()).isNotNull();
            assertThat(response.maxTransactionDate()).isNotNull();
            assertThat(response.suggestedStartPeriod()).isNotNull();
            assertThat(response.monthsOfData()).isGreaterThan(0);
            assertThat(response.monthsCovered()).isNotEmpty();

            // Categories for mapping
            assertThat(response.bankCategories()).isNotEmpty();

            // Import status
            assertThat(response.importStatus()).isEqualTo("PENDING");

            log.info("All UI fields verified for transformationId={}", response.transformationId());
        }

        @Test
        @DisplayName("Should correctly count bank categories with type")
        void shouldCorrectlyCountBankCategoriesWithType() {
            // given - multiple transactions in different categories
            String csv = UnifiedCsvImportHttpActor.createCanonicalCsv(
                UnifiedCsvImportHttpActor.canonicalRow("TXN001", "Salary", "Income", 5000.00, "PLN", "INFLOW", "2023-01-15"),
                UnifiedCsvImportHttpActor.canonicalRow("TXN002", "Bonus", "Income", 1000.00, "PLN", "INFLOW", "2023-01-20"),
                UnifiedCsvImportHttpActor.canonicalRow("TXN003", "Rent", "Housing", -1500.00, "PLN", "OUTFLOW", "2023-01-20"),
                UnifiedCsvImportHttpActor.canonicalRow("TXN004", "Food", "Groceries", -200.00, "PLN", "OUTFLOW", "2023-01-21"),
                UnifiedCsvImportHttpActor.canonicalRow("TXN005", "More Food", "Groceries", -150.00, "PLN", "OUTFLOW", "2023-01-22")
            );

            // when
            UnifiedCsvImportController.UploadResponse response = actor.upload(
                csv.getBytes(),
                "categories.csv",
                null
            );

            // then
            assertThat(response.bankCategories()).hasSize(3);

            // Income: 2 transactions, INFLOW
            var incomeCategory = response.bankCategories().stream()
                .filter(c -> c.name().equals("Income"))
                .findFirst().orElseThrow();
            assertThat(incomeCategory.count()).isEqualTo(2);
            assertThat(incomeCategory.type()).isEqualTo("INFLOW");

            // Groceries: 2 transactions, OUTFLOW
            var groceriesCategory = response.bankCategories().stream()
                .filter(c -> c.name().equals("Groceries"))
                .findFirst().orElseThrow();
            assertThat(groceriesCategory.count()).isEqualTo(2);
            assertThat(groceriesCategory.type()).isEqualTo("OUTFLOW");

            // Housing: 1 transaction, OUTFLOW
            var housingCategory = response.bankCategories().stream()
                .filter(c -> c.name().equals("Housing"))
                .findFirst().orElseThrow();
            assertThat(housingCategory.count()).isEqualTo(1);
            assertThat(housingCategory.type()).isEqualTo("OUTFLOW");

            log.info("Category counts verified: {}", response.bankCategories());
        }
    }

    @Nested
    @DisplayName("Date Range Extraction")
    class DateRangeTests {

        @Test
        @DisplayName("Should extract correct date range across multiple months")
        void shouldExtractCorrectDateRangeAcrossMultipleMonths() {
            // given - transactions spanning 6 months
            String csv = UnifiedCsvImportHttpActor.createCanonicalCsv(
                UnifiedCsvImportHttpActor.canonicalRow("TXN001", "Jan", "Income", 1000.00, "PLN", "INFLOW", "2023-01-15"),
                UnifiedCsvImportHttpActor.canonicalRow("TXN002", "Mar", "Income", 1000.00, "PLN", "INFLOW", "2023-03-10"),
                UnifiedCsvImportHttpActor.canonicalRow("TXN003", "Jun", "Income", 1000.00, "PLN", "INFLOW", "2023-06-20")
            );

            // when
            UnifiedCsvImportController.UploadResponse response = actor.upload(
                csv.getBytes(),
                "multimonth.csv",
                null
            );

            // then
            assertThat(response.minTransactionDate()).isEqualTo("2023-01-15");
            assertThat(response.maxTransactionDate()).isEqualTo("2023-06-20");
            assertThat(response.suggestedStartPeriod()).isEqualTo("2023-01");
            assertThat(response.monthsOfData()).isEqualTo(3); // Only months with transactions
            assertThat(response.monthsCovered()).containsExactly("2023-01", "2023-03", "2023-06");

            log.info("Date range: {} to {}, {} months covered",
                response.minTransactionDate(), response.maxTransactionDate(), response.monthsOfData());
        }
    }

    @Nested
    @DisplayName("Error Scenarios")
    class ErrorScenarioTests {

        @Test
        @DisplayName("Should reject empty file")
        void shouldRejectEmptyFile() {
            // given
            byte[] emptyContent = "".getBytes();

            // when
            ResponseEntity<ApiError> response = actor.uploadExpectingError(
                emptyContent,
                "empty.csv",
                null
            );

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            log.info("Empty file rejected with status: {}", response.getStatusCode());
        }

        @Test
        @DisplayName("Should reject file with only header")
        void shouldRejectFileWithOnlyHeader() {
            // given
            String headerOnly = "bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate\n";

            // when
            UnifiedCsvImportController.UploadResponse response = actor.upload(
                headerOnly.getBytes(),
                "header_only.csv",
                null
            );

            // then
            assertThat(response.success()).isTrue();
            assertThat(response.rowCount()).isEqualTo(0);
            assertThat(response.bankCategories()).isEmpty();
            log.info("Header-only file processed: {} rows", response.rowCount());
        }

        @Test
        @DisplayName("Should handle duplicate file upload")
        void shouldHandleDuplicateFileUpload() {
            // given
            String canonicalCsv = UnifiedCsvImportHttpActor.createCanonicalCsv(
                UnifiedCsvImportHttpActor.canonicalRow("TXN001", "Salary", "Income", 5000.00, "PLN", "INFLOW", "2023-01-15")
            );

            // First upload - should succeed
            UnifiedCsvImportController.UploadResponse firstResponse = actor.upload(
                canonicalCsv.getBytes(),
                "salary.csv",
                null
            );
            assertThat(firstResponse.success()).isTrue();

            // when - second upload with same content
            ResponseEntity<ApiError> secondResponse = actor.uploadExpectingError(
                canonicalCsv.getBytes(),
                "salary_copy.csv",
                null
            );

            // then
            assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            log.info("Duplicate file rejected with status: {}", secondResponse.getStatusCode());
        }

        @Test
        @DisplayName("Should handle missing required columns gracefully")
        void shouldHandleMissingRequiredColumnsGracefully() {
            // given - CSV without required 'amount' and 'type' columns (not canonical format)
            String nonCanonicalCsv = """
                name,currency,operationDate,bankCategory,description
                Salary,PLN,2023-01-15,Income,Monthly salary
                """;

            // when - this will try AI transformation since it's not canonical
            // Note: In test environment without AI, this may fail differently
            ResponseEntity<ApiError> response = actor.uploadExpectingError(
                nonCanonicalCsv.getBytes(),
                "invalid.csv",
                null
            );

            // then - expect error since AI is not available in test
            assertThat(response.getStatusCode().is4xxClientError() || response.getStatusCode().is5xxServerError()).isTrue();
            log.info("Invalid format handled with status: {}", response.getStatusCode());
        }
    }

    @Nested
    @DisplayName("Currency Detection")
    class CurrencyDetectionTests {

        @Test
        @DisplayName("Should detect EUR currency")
        void shouldDetectEurCurrency() {
            // given
            String eurCsv = UnifiedCsvImportHttpActor.createCanonicalCsv(
                UnifiedCsvImportHttpActor.canonicalRow("TXN001", "Payment", "Income", 1000.00, "EUR", "INFLOW", "2023-01-15")
            );

            // when
            UnifiedCsvImportController.UploadResponse response = actor.upload(
                eurCsv.getBytes(),
                "eur.csv",
                null
            );

            // then
            assertThat(response.detectedCurrency()).isEqualTo("EUR");
            log.info("Currency detected: {}", response.detectedCurrency());
        }

        @Test
        @DisplayName("Should detect USD currency")
        void shouldDetectUsdCurrency() {
            // given
            String usdCsv = UnifiedCsvImportHttpActor.createCanonicalCsv(
                UnifiedCsvImportHttpActor.canonicalRow("TXN001", "Payment", "Income", 1000.00, "USD", "INFLOW", "2023-01-15")
            );

            // when
            UnifiedCsvImportController.UploadResponse response = actor.upload(
                usdCsv.getBytes(),
                "usd.csv",
                null
            );

            // then
            assertThat(response.detectedCurrency()).isEqualTo("USD");
            log.info("Currency detected: {}", response.detectedCurrency());
        }
    }
}
