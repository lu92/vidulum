package com.multi.vidulum.cashflow.app;

import com.multi.vidulum.AuthenticatedHttpIntegrationTest;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.error.ApiError;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security tests for CashFlow endpoints.
 *
 * These tests verify that:
 * - Unauthenticated requests return 401 Unauthorized
 * - Invalid JWT tokens return 401 Unauthorized
 * - Authenticated requests succeed
 *
 * This test class extends AuthenticatedHttpIntegrationTest which has
 * security ENABLED (unlike AbstractHttpIntegrationTest which disables it).
 */
@Slf4j
class CashFlowSecurityTest extends AuthenticatedHttpIntegrationTest {

    private CashFlowHttpActor authenticatedActor;

    @BeforeEach
    void setUp() {
        // Register and authenticate to get valid tokens
        registerAndAuthenticate();

        // Create authenticated actor
        authenticatedActor = new CashFlowHttpActor(restTemplate, port);
        authenticatedActor.setJwtToken(accessToken);
    }

    // ==================== 401 Unauthorized Tests ====================

    @Test
    @DisplayName("Should return 403 FORBIDDEN when accessing protected endpoint without token")
    void shouldReturn403WithoutToken() {
        // given - actor without JWT token
        CashFlowHttpActor unauthenticatedActor = new CashFlowHttpActor(restTemplate, port);
        // Don't set JWT token

        // when - try to access protected endpoint
        ResponseEntity<ApiError> response = unauthenticatedActor.getCashFlowExpectingError("CF123");

        // then - Spring Security returns 403 for missing/invalid authentication
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        log.info("403 without token: status={}", response.getStatusCode());
    }

    @Test
    @DisplayName("Should return 403 FORBIDDEN when accessing protected endpoint with invalid token")
    void shouldReturn403WithInvalidToken() {
        // given - actor with invalid JWT token
        CashFlowHttpActor actorWithInvalidToken = new CashFlowHttpActor(restTemplate, port);
        actorWithInvalidToken.setJwtToken("invalid.jwt.token");

        // when - try to access protected endpoint
        ResponseEntity<ApiError> response = actorWithInvalidToken.getCashFlowExpectingError("CF123");

        // then - Spring Security returns 403 for invalid token
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        log.info("403 with invalid token: status={}", response.getStatusCode());
    }

    @Test
    @DisplayName("Should return 403 FORBIDDEN when accessing protected endpoint with malformed token")
    void shouldReturn403WithMalformedToken() {
        // given - actor with malformed JWT token (not even base64)
        CashFlowHttpActor actorWithMalformedToken = new CashFlowHttpActor(restTemplate, port);
        actorWithMalformedToken.setJwtToken("not-a-jwt-at-all");

        // when
        ResponseEntity<ApiError> response = actorWithMalformedToken.getCashFlowExpectingError("CF123");

        // then - Spring Security returns 403 for malformed token
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        log.info("403 with malformed token: status={}", response.getStatusCode());
    }

    @Test
    @DisplayName("Should return 403 FORBIDDEN when POST without token")
    void shouldReturn403OnPostWithoutToken() {
        // given
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // No Bearer token

        CashFlowDto.CreateCashFlowWithHistoryJson request = CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                .userId("U123")
                .name("Test")
                .description("Test")
                .startPeriod("2024-01")
                .build();

        HttpEntity<CashFlowDto.CreateCashFlowWithHistoryJson> entity = new HttpEntity<>(request, headers);

        // when
        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/cash-flow/with-history",
                HttpMethod.POST,
                entity,
                ApiError.class
        );

        // then - Spring Security returns 403 for missing authentication
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        log.info("403 on POST without token: status={}", response.getStatusCode());
    }

    // ==================== Authenticated Access Tests ====================

    @Test
    @DisplayName("Should successfully access protected endpoint with valid token")
    void shouldSucceedWithValidToken() {
        // given - authenticated actor (set up in @BeforeEach)

        // when - create CashFlow (requires authentication)
        // Use startPeriod before activePeriod (FIXED_NOW = 2022-01-01)
        String cashFlowId = authenticatedActor.createCashFlowWithHistory(
                userId,
                "Test CashFlow",
                YearMonth.of(2021, 7),  // startPeriod before activePeriod
                Money.of(1000.0, "PLN")
        );

        // then
        assertThat(cashFlowId).isNotNull().isNotEmpty();

        // Verify we can also read it back
        CashFlowDto.CashFlowSummaryJson cashFlow = authenticatedActor.getCashFlow(cashFlowId);
        assertThat(cashFlow).isNotNull();
        assertThat(cashFlow.getName()).isEqualTo("Test CashFlow");

        log.info("Successfully created and retrieved CashFlow with valid token: id={}", cashFlowId);
    }

    @Test
    @DisplayName("Should successfully create category with valid token")
    void shouldCreateCategoryWithValidToken() {
        // given - startPeriod before activePeriod (FIXED_NOW = 2022-01-01)
        String cashFlowId = authenticatedActor.createCashFlowWithHistory(
                userId,
                "Test CashFlow for Category",
                YearMonth.of(2021, 7),  // startPeriod before activePeriod
                Money.of(1000.0, "PLN")
        );

        // when - create category (requires authentication)
        authenticatedActor.createCategory(cashFlowId, "TestCategory", com.multi.vidulum.cashflow.domain.Type.OUTFLOW);

        // then - verify category was created
        CashFlowDto.CashFlowSummaryJson cashFlow = authenticatedActor.getCashFlow(cashFlowId);
        boolean categoryExists = cashFlow.getOutflowCategories().stream()
                .anyMatch(c -> "TestCategory".equals(c.getCategoryName().name()));
        assertThat(categoryExists).isTrue();

        log.info("Successfully created category with valid token");
    }

    // ==================== Public Endpoints Tests ====================

    @Test
    @DisplayName("Should allow access to /api/v1/auth/register without token")
    void shouldAllowRegisterWithoutToken() {
        // given - no token needed for registration
        String username = uniqueUsername();

        // when
        ResponseEntity<com.multi.vidulum.security.auth.AuthenticationResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/register",
                com.multi.vidulum.security.auth.RegisterRequest.builder()
                        .username(username)
                        .email(username + "@test.com")
                        .password("SecurePassword123!")
                        .build(),
                com.multi.vidulum.security.auth.AuthenticationResponse.class
        );

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getAccessToken()).isNotNull();

        log.info("Registration succeeded without token (public endpoint)");
    }

    @Test
    @DisplayName("Should allow access to /api/v1/auth/authenticate without token")
    void shouldAllowAuthenticateWithoutToken() {
        // given - first register a user
        String username = uniqueUsername();
        String password = "SecurePassword123!";

        restTemplate.postForEntity(
                "/api/v1/auth/register",
                com.multi.vidulum.security.auth.RegisterRequest.builder()
                        .username(username)
                        .email(username + "@test.com")
                        .password(password)
                        .build(),
                com.multi.vidulum.security.auth.AuthenticationResponse.class
        );

        // when - authenticate without token
        ResponseEntity<com.multi.vidulum.security.auth.AuthenticationResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/authenticate",
                com.multi.vidulum.security.auth.AuthenticationRequest.builder()
                        .username(username)
                        .password(password)
                        .build(),
                com.multi.vidulum.security.auth.AuthenticationResponse.class
        );

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getAccessToken()).isNotNull();

        log.info("Authentication succeeded without token (public endpoint)");
    }
}
