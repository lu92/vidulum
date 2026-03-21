package com.multi.vidulum.bank_data_adapter.app;

import com.multi.vidulum.AuthenticatedHttpIntegrationTest;
import com.multi.vidulum.common.error.ApiError;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP error handling tests for AI Bank CSV Adapter API.
 * Verifies that all business exceptions return proper HTTP status codes
 * and well-formatted ApiError responses.
 *
 * Pattern follows BankDataIngestionErrorHandlingTest for consistency.
 */
@Slf4j
@DisplayName("AI Bank CSV Adapter - Error Handling")
class AiBankCsvAdapterErrorHandlingTest extends AuthenticatedHttpIntegrationTest {

    private AiBankCsvAdapterHttpActor actor;

    @BeforeEach
    void setUp() {
        registerAndAuthenticate();
        actor = new AiBankCsvAdapterHttpActor(restTemplate, port);
        actor.setJwtToken(accessToken);
    }

    // ============ 400 BAD_REQUEST Tests ============

    @Nested
    @DisplayName("400 BAD_REQUEST - Validation Errors")
    class BadRequestTests {

        @Test
        @DisplayName("Should return 400 BAD_REQUEST with AI_ADAPTER_EMPTY_FILE when file is empty")
        void shouldReturn400WhenEmptyFile() {
            // given
            byte[] emptyContent = new byte[0];

            // when
            ResponseEntity<ApiError> response = actor.transformExpectingError(
                    "empty.csv", emptyContent, null);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();

            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("AI_ADAPTER_EMPTY_FILE");
            assertThat(error.message()).isNotBlank();
            assertThat(error.fieldErrors()).isNull();
            assertThat(error.timestamp()).isNotNull();

            log.info("✅ Empty file rejected: message={}", error.message());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST with AI_ADAPTER_FILE_TOO_LARGE when file exceeds limit")
        void shouldReturn400WhenFileTooLarge() {
            // given - create 6MB file (limit is 5MB)
            byte[] largeContent = new byte[6 * 1024 * 1024];

            // when
            ResponseEntity<ApiError> response = actor.transformExpectingError(
                    "large.csv", largeContent, null);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();

            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("AI_ADAPTER_FILE_TOO_LARGE");
            assertThat(error.message())
                    .as("Error message should contain file size information")
                    .containsPattern("\\d+.*bytes");
            assertThat(error.fieldErrors()).isNull();
            assertThat(error.timestamp()).isNotNull();

            log.info("✅ Large file rejected: message={}", error.message());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST with AI_ADAPTER_INVALID_FILE_TYPE when file is not CSV")
        void shouldReturn400WhenInvalidFileType() {
            // given
            byte[] content = "some content".getBytes(StandardCharsets.UTF_8);

            // when
            ResponseEntity<ApiError> response = actor.transformExpectingError(
                    "document.pdf", content, null);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();

            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("AI_ADAPTER_INVALID_FILE_TYPE");
            assertThat(error.message())
                    .as("Error message should contain the detected type")
                    .contains(".pdf");
            assertThat(error.fieldErrors()).isNull();
            assertThat(error.timestamp()).isNotNull();

            log.info("✅ Invalid file type rejected: message={}", error.message());
        }

        @Test
        @DisplayName("Should return 400 BAD_REQUEST with AI_ADAPTER_INVALID_TRANSFORMATION_ID when ID format is invalid")
        void shouldReturn400WhenInvalidTransformationId() {
            // given
            String invalidId = "not-a-uuid";

            // when
            ResponseEntity<ApiError> response = actor.getTransformationExpectingError(invalidId);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();

            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("AI_ADAPTER_INVALID_TRANSFORMATION_ID");
            assertThat(error.message())
                    .as("Error message should contain the invalid ID")
                    .contains(invalidId);
            assertThat(error.fieldErrors()).isNull();
            assertThat(error.timestamp()).isNotNull();

            log.info("✅ Invalid transformation ID rejected: message={}", error.message());
        }
    }

    // ============ 404 NOT_FOUND Tests ============

    @Nested
    @DisplayName("404 NOT_FOUND - Resources Not Found")
    class NotFoundTests {

        @Test
        @DisplayName("Should return 404 NOT_FOUND with AI_ADAPTER_TRANSFORMATION_NOT_FOUND when transformation does not exist")
        void shouldReturn404WhenTransformationNotFound() {
            // given
            String nonExistentId = "550e8400-e29b-41d4-a716-446655440000";

            // when
            ResponseEntity<ApiError> response = actor.getTransformationExpectingError(nonExistentId);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();

            ApiError error = response.getBody();
            assertThat(error.status()).isEqualTo(404);
            assertThat(error.code()).isEqualTo("AI_ADAPTER_TRANSFORMATION_NOT_FOUND");
            assertThat(error.message())
                    .as("Error message should contain the transformation ID")
                    .contains(nonExistentId);
            assertThat(error.fieldErrors()).isNull();
            assertThat(error.timestamp()).isNotNull();

            log.info("✅ Transformation not found: id={}, message={}", nonExistentId, error.message());
        }
    }

    // ============ 409 CONFLICT Tests ============

    @Nested
    @DisplayName("409 CONFLICT - Resource Conflicts")
    @Disabled("Requires AI service mock to create initial transformation for duplicate test")
    class ConflictTests {

        @Test
        @DisplayName("Should return 409 CONFLICT with AI_ADAPTER_DUPLICATE_FILE when same file uploaded twice")
        void shouldReturn409WhenDuplicateFile() {
            // This test requires mocking AI service to create initial transformation
            // Will be enabled when we have proper test fixtures
        }

        @Test
        @DisplayName("Should return 409 CONFLICT with AI_ADAPTER_ALREADY_IMPORTED when importing twice")
        void shouldReturn409WhenAlreadyImported() {
            // This test requires mocking AI service to create transformation and import it
            // Will be enabled when we have proper test fixtures
        }
    }

    // ============ ApiError Format Verification ============

    @Nested
    @DisplayName("ApiError Format Verification")
    class ApiErrorFormatTests {

        @Test
        @DisplayName("Should return properly formatted ApiError with all required fields")
        void shouldReturnProperlyFormattedApiError() {
            // given
            String invalidId = "invalid-format";

            // when
            ResponseEntity<ApiError> response = actor.getTransformationExpectingError(invalidId);

            // then
            assertThat(response.getBody()).isNotNull();
            ApiError error = response.getBody();

            // Required fields
            assertThat(error.status()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("AI_ADAPTER_INVALID_TRANSFORMATION_ID");
            assertThat(error.message()).isNotBlank();
            assertThat(error.timestamp()).isNotNull();

            // fieldErrors should be null for non-validation errors
            assertThat(error.fieldErrors()).isNull();

            log.info("✅ ApiError format verified: status={}, code={}, timestamp={}",
                    error.status(), error.code(), error.timestamp());
        }
    }
}
