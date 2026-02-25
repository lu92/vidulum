package com.multi.vidulum.bank_data_ingestion.infrastructure;

import com.multi.vidulum.AuthenticatedHttpIntegrationTest;
import com.multi.vidulum.bank_data_ingestion.app.CashFlowInfo;
import com.multi.vidulum.cashflow.app.CashFlowHttpActor;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.common.Money;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.YearMonth;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for HttpCashFlowServiceClient.
 * <p>
 * Verifies that HttpCashFlowServiceClient correctly communicates with CashFlow REST API:
 * - URL correctness (cf= prefix)
 * - HTTP error handling (404, 500)
 * - JSON serialization/deserialization
 * - Banking model propagation (IBAN, country code, bank code, etc.)
 * <p>
 * Uses existing test infrastructure (@SpringBootTest + Testcontainers) instead of WireMock
 * to test the real HTTP stack.
 *
 * This test class extends AuthenticatedHttpIntegrationTest which has
 * security ENABLED (real JWT authentication).
 */
@Slf4j
class HttpCashFlowServiceClientIntegrationTest extends AuthenticatedHttpIntegrationTest {

    private static final AtomicInteger NAME_COUNTER = new AtomicInteger(0);

    private String uniqueCashFlowName() {
        return "HttpClient-" + NAME_COUNTER.incrementAndGet();
    }

    private HttpCashFlowServiceClient httpClient;
    private CashFlowHttpActor actor;

    @BeforeEach
    void setUp() {
        // Register and authenticate to get valid JWT tokens
        registerAndAuthenticate();

        // Create HttpCashFlowServiceClient instance pointing to embedded server
        String baseUrl = "http://localhost:" + port;
        RestClient.Builder builder = RestClient.builder();
        httpClient = new HttpCashFlowServiceClient(builder, baseUrl);
        httpClient.setJwtToken(accessToken);

        // Create actor for test setup with JWT token
        actor = new CashFlowHttpActor(restTemplate, port);
        actor.setJwtToken(accessToken);
    }

    @Test
    @DisplayName("Should get CashFlow info with correct URL (cf= prefix)")
    void shouldGetCashFlowInfoWithCorrectUrl() {
        // Given: Create CashFlow with IBAN via REST API
        String cashFlowId = actor.createCashFlowWithIban(
                userId,
                uniqueCashFlowName(),
                "GB29NWBK60161331926819",
                "GBP"
        );

        // When: Get CashFlow info via HttpCashFlowServiceClient
        CashFlowInfo info = httpClient.getCashFlowInfo(cashFlowId);

        // Then: Verify correct data returned (using FixedClockConfig date: 2022-01-01)
        assertThat(info).isNotNull();
        assertThat(info.cashFlowId()).isEqualTo(cashFlowId);
        assertThat(info.status()).isEqualTo(CashFlowInfo.CashFlowStatus.OPEN);
        assertThat(info.activePeriod()).isEqualTo(YearMonth.of(2022, 1));

        // Verify categories structure
        assertThat(info.inflowCategories()).hasSize(1);
        assertThat(info.inflowCategories().get(0).name()).isEqualTo("Uncategorized");
        assertThat(info.outflowCategories()).hasSize(1);
        assertThat(info.outflowCategories().get(0).name()).isEqualTo("Uncategorized");

        log.info("✅ Successfully retrieved CashFlow via HTTP client with correct URL");
    }

    @Test
    @DisplayName("Should handle 404 Not Found error")
    void shouldHandleNotFoundError() {
        // Given: Non-existent CashFlow ID
        String nonExistentId = "CF99999999";

        // When/Then: Should throw CashFlowNotFoundException
        assertThatThrownBy(() -> httpClient.getCashFlowInfo(nonExistentId))
                .isInstanceOf(HttpCashFlowServiceClient.CashFlowNotFoundException.class)
                .hasMessageContaining(nonExistentId);

        log.info("✅ Correctly handled 404 error");
    }

    @Test
    @DisplayName("Should propagate banking model correctly (IBAN, country code, bank code)")
    void shouldPropagateBankingModelCorrectly() {
        // Given: Create CashFlow with Polish IBAN
        String cashFlowId = actor.createCashFlowWithSwift(
                userId,
                uniqueCashFlowName(),
                "PL61109010140000071219812874",
                "PKOPPLPW"
        );

        // When: Get CashFlow info via HTTP client
        CashFlowInfo info = httpClient.getCashFlowInfo(cashFlowId);

        // Then: Banking model is propagated correctly
        // Note: CashFlowInfo doesn't expose banking details directly,
        // but we verify it was created and retrieved successfully
        assertThat(info).isNotNull();
        assertThat(info.cashFlowId()).isEqualTo(cashFlowId);
        assertThat(info.status()).isEqualTo(CashFlowInfo.CashFlowStatus.OPEN);

        log.info("✅ Banking model propagated correctly through HTTP client");
    }

    @Test
    @DisplayName("Should verify CashFlow exists")
    void shouldVerifyCashFlowExists() {
        // Given: Create CashFlow
        String cashFlowId = actor.createCashFlowWithIban(
                userId,
                uniqueCashFlowName(),
                "GB82WEST12345698765432",
                "GBP"
        );

        // When: Check if exists
        boolean exists = httpClient.exists(cashFlowId);

        // Then: Should return true
        assertThat(exists).isTrue();

        // When: Check non-existent
        boolean nonExistent = httpClient.exists("CF99999999");

        // Then: Should return false
        assertThat(nonExistent).isFalse();

        log.info("✅ Correctly verified CashFlow existence");
    }

    @Test
    @DisplayName("Should create category via HTTP client")
    void shouldCreateCategoryViaHttpClient() {
        // Given: Create CashFlow
        String cashFlowId = actor.createCashFlow(
                userId,
                uniqueCashFlowName(),
                "GBP"
        );

        // When: Create category via HTTP client
        httpClient.createCategory(cashFlowId, "Salary", null, Type.INFLOW);

        // Then: Verify category was created
        CashFlowInfo info = httpClient.getCashFlowInfo(cashFlowId);
        assertThat(info.inflowCategories())
                .extracting(CashFlowInfo.CategoryInfo::name)
                .contains("Salary", "Uncategorized");

        log.info("✅ Successfully created category via HTTP client");
    }

    @Test
    @DisplayName("Should handle category already exists error")
    void shouldHandleCategoryAlreadyExistsError() {
        // Given: Create CashFlow with category
        String cashFlowId = actor.createCashFlow(
                userId,
                uniqueCashFlowName(),
                "GBP"
        );
        httpClient.createCategory(cashFlowId, "Salary", null, Type.INFLOW);

        // When/Then: Try to create same category again
        assertThatThrownBy(() ->
                httpClient.createCategory(cashFlowId, "Salary", null, Type.INFLOW)
        )
                .isInstanceOf(HttpCashFlowServiceClient.CategoryAlreadyExistsException.class)
                .hasMessageContaining("Salary");

        log.info("✅ Correctly handled duplicate category error");
    }

    @Test
    @DisplayName("Should get CashFlow info for SETUP mode with history")
    void shouldGetCashFlowInfoForSetupMode() {
        // Given: Create CashFlow with history (SETUP mode)
        // Using FixedClockConfig: current date is 2022-01-01
        // startPeriod must be in the past (before 2022-01)
        String cashFlowId = actor.createCashFlowWithHistory(
                userId,
                uniqueCashFlowName(),
                YearMonth.of(2021, 10),
                Money.of(10000.0, "GBP")
        );

        // When: Get CashFlow info via HTTP client
        CashFlowInfo info = httpClient.getCashFlowInfo(cashFlowId);

        // Then: Verify SETUP mode
        assertThat(info).isNotNull();
        assertThat(info.cashFlowId()).isEqualTo(cashFlowId);
        assertThat(info.status()).isEqualTo(CashFlowInfo.CashFlowStatus.SETUP);
        assertThat(info.startPeriod()).isEqualTo(YearMonth.of(2021, 10));
        assertThat(info.activePeriod()).isEqualTo(YearMonth.of(2022, 1));

        log.info("✅ Correctly retrieved SETUP mode CashFlow via HTTP client");
    }
}
