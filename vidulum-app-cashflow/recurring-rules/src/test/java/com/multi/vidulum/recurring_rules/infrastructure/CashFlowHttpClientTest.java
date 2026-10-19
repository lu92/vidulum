package com.multi.vidulum.recurring_rules.infrastructure;

import com.multi.vidulum.common.CashChangeId;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CashFlowHttpClient.
 * <p>
 * Tests specifically cover the nested category extraction logic to prevent
 * regression of the bug where subcategories were not recognized.
 * <p>
 * See: VID-131 - Category [Medicine] not found in CashFlow when using nested categories
 */
@ExtendWith(MockitoExtension.class)
class CashFlowHttpClientTest {

    @Mock
    private RestTemplate restTemplate;

    private CashFlowHttpClient cashFlowHttpClient;

    private static final String AUTH_TOKEN = "test-token";
    private static final CashFlowId CASH_FLOW_ID = CashFlowId.of("CF10000001");

    @BeforeEach
    void setUp() {
        cashFlowHttpClient = new CashFlowHttpClient(restTemplate);
    }

    // The five cases that used to stand here drove getCashFlowInfo and validateCategoryExists,
    // both of which are gone: they were reads CashFlowSkeletonView already answers, and the
    // recursive walk they exercised was this module's own copy of what the contract computes.
    // Nothing is left uncovered - CashFlowSkeletonComponentTest asserts the same flattening and
    // the same per-direction separation on inflowCategoryNames()/outflowCategoryNames(), which
    // is what RecurringRuleService.validateCategory now uses.

    // ==================== Batch Delete Tests ====================

    @Nested
    class BatchDeleteExpectedCashChangesTests {

        private static final RecurringRuleId RULE_ID = RecurringRuleId.of("RR00000001");

        @Test
        void shouldBatchDeleteExpectedCashChanges() throws Exception {
            // GIVEN
            List<CashChangeId> cashChangeIds = List.of(
                    CashChangeId.of("CC1000000001"),
                    CashChangeId.of("CC1000000002"),
                    CashChangeId.of("CC1000000003")
            );

            Map<String, Object> response = Map.of(
                    "deletedCount", 3,
                    "skippedCount", 0
            );

            when(restTemplate.exchange(
                    anyString(),
                    eq(HttpMethod.DELETE),
                    any(HttpEntity.class),
                    eq(Map.class)
            )).thenReturn(new ResponseEntity<>(response, HttpStatus.OK));

            // WHEN
            CashFlowHttpClient.BatchDeleteResult result = cashFlowHttpClient.batchDeleteExpectedCashChanges(
                    CASH_FLOW_ID, RULE_ID, cashChangeIds, AUTH_TOKEN
            );

            // THEN
            assertThat(result.deletedCount()).isEqualTo(3);
            assertThat(result.skippedCount()).isEqualTo(0);
        }

        @Test
        void shouldReturnSkippedCountForConfirmedCashChanges() throws Exception {
            // GIVEN: 2 deleted, 1 skipped (confirmed)
            List<CashChangeId> cashChangeIds = List.of(
                    CashChangeId.of("CC1000000001"),
                    CashChangeId.of("CC1000000002"),
                    CashChangeId.of("CC1000000003")
            );

            Map<String, Object> response = Map.of(
                    "deletedCount", 2,
                    "skippedCount", 1
            );

            when(restTemplate.exchange(
                    anyString(),
                    eq(HttpMethod.DELETE),
                    any(HttpEntity.class),
                    eq(Map.class)
            )).thenReturn(new ResponseEntity<>(response, HttpStatus.OK));

            // WHEN
            CashFlowHttpClient.BatchDeleteResult result = cashFlowHttpClient.batchDeleteExpectedCashChanges(
                    CASH_FLOW_ID, RULE_ID, cashChangeIds, AUTH_TOKEN
            );

            // THEN
            assertThat(result.deletedCount()).isEqualTo(2);
            assertThat(result.skippedCount()).isEqualTo(1);
        }

        @Test
        void shouldReturnZeroCountsForEmptyList() throws Exception {
            // GIVEN: Empty list
            List<CashChangeId> cashChangeIds = List.of();

            // WHEN
            CashFlowHttpClient.BatchDeleteResult result = cashFlowHttpClient.batchDeleteExpectedCashChanges(
                    CASH_FLOW_ID, RULE_ID, cashChangeIds, AUTH_TOKEN
            );

            // THEN: Should return zeros without making HTTP call
            assertThat(result.deletedCount()).isEqualTo(0);
            assertThat(result.skippedCount()).isEqualTo(0);
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldSendCorrectRequestBody() throws Exception {
            // GIVEN
            List<CashChangeId> cashChangeIds = List.of(
                    CashChangeId.of("CC1000000001"),
                    CashChangeId.of("CC1000000002")
            );

            Map<String, Object> response = Map.of("deletedCount", 2, "skippedCount", 0);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);

            when(restTemplate.exchange(
                    anyString(),
                    eq(HttpMethod.DELETE),
                    entityCaptor.capture(),
                    eq(Map.class)
            )).thenReturn(new ResponseEntity<>(response, HttpStatus.OK));

            // WHEN
            cashFlowHttpClient.batchDeleteExpectedCashChanges(
                    CASH_FLOW_ID, RULE_ID, cashChangeIds, AUTH_TOKEN
            );

            // THEN: Verify request body
            HttpEntity<Map<String, Object>> capturedEntity = entityCaptor.getValue();
            Map<String, Object> requestBody = capturedEntity.getBody();

            assertThat(requestBody).containsKey("sourceRuleId");
            assertThat(requestBody.get("sourceRuleId")).isEqualTo("RR00000001");
            assertThat(requestBody).containsKey("cashChangeIds");
            assertThat((List<String>) requestBody.get("cashChangeIds"))
                    .containsExactly("CC1000000001", "CC1000000002");
        }
    }

    // ==================== Batch Update Tests ====================

    @Nested
    class BatchUpdateExpectedCashChangesTests {

        private static final RecurringRuleId RULE_ID = RecurringRuleId.of("RR00000001");

        @Test
        void shouldBatchUpdateExpectedCashChanges() throws Exception {
            // GIVEN
            List<CashChangeId> cashChangeIds = List.of(
                    CashChangeId.of("CC1000000001"),
                    CashChangeId.of("CC1000000002")
            );

            CashFlowHttpClient.CashChangeUpdates updates = new CashFlowHttpClient.CashChangeUpdates(
                    Money.of(500, "PLN"),
                    "Updated Name",
                    new CategoryName("NewCategory")
            );

            Map<String, Object> response = Map.of(
                    "updatedCount", 2,
                    "skippedCount", 0
            );

            when(restTemplate.exchange(
                    anyString(),
                    eq(HttpMethod.PATCH),
                    any(HttpEntity.class),
                    eq(Map.class)
            )).thenReturn(new ResponseEntity<>(response, HttpStatus.OK));

            // WHEN
            CashFlowHttpClient.BatchUpdateResult result = cashFlowHttpClient.batchUpdateExpectedCashChanges(
                    CASH_FLOW_ID, RULE_ID, cashChangeIds, updates, AUTH_TOKEN
            );

            // THEN
            assertThat(result.updatedCount()).isEqualTo(2);
            assertThat(result.skippedCount()).isEqualTo(0);
        }

        @Test
        void shouldReturnSkippedCountForConfirmedCashChanges() throws Exception {
            // GIVEN
            List<CashChangeId> cashChangeIds = List.of(
                    CashChangeId.of("CC1000000001"),
                    CashChangeId.of("CC1000000002"),
                    CashChangeId.of("CC1000000003")
            );

            CashFlowHttpClient.CashChangeUpdates updates = CashFlowHttpClient.CashChangeUpdates.ofAmount(
                    Money.of(1000, "PLN")
            );

            Map<String, Object> response = Map.of(
                    "updatedCount", 2,
                    "skippedCount", 1
            );

            when(restTemplate.exchange(
                    anyString(),
                    eq(HttpMethod.PATCH),
                    any(HttpEntity.class),
                    eq(Map.class)
            )).thenReturn(new ResponseEntity<>(response, HttpStatus.OK));

            // WHEN
            CashFlowHttpClient.BatchUpdateResult result = cashFlowHttpClient.batchUpdateExpectedCashChanges(
                    CASH_FLOW_ID, RULE_ID, cashChangeIds, updates, AUTH_TOKEN
            );

            // THEN
            assertThat(result.updatedCount()).isEqualTo(2);
            assertThat(result.skippedCount()).isEqualTo(1);
        }

        @Test
        void shouldReturnZeroCountsForEmptyList() throws Exception {
            // GIVEN
            List<CashChangeId> cashChangeIds = List.of();
            CashFlowHttpClient.CashChangeUpdates updates = CashFlowHttpClient.CashChangeUpdates.ofName("Test");

            // WHEN
            CashFlowHttpClient.BatchUpdateResult result = cashFlowHttpClient.batchUpdateExpectedCashChanges(
                    CASH_FLOW_ID, RULE_ID, cashChangeIds, updates, AUTH_TOKEN
            );

            // THEN
            assertThat(result.updatedCount()).isEqualTo(0);
            assertThat(result.skippedCount()).isEqualTo(0);
        }

        @Test
        void shouldCreateUpdatesWithFactoryMethods() {
            // Test factory methods for CashChangeUpdates

            // ofAmount
            CashFlowHttpClient.CashChangeUpdates amountUpdates = CashFlowHttpClient.CashChangeUpdates.ofAmount(
                    Money.of(100, "PLN")
            );
            assertThat(amountUpdates.amount()).isEqualTo(Money.of(100, "PLN"));
            assertThat(amountUpdates.name()).isNull();
            assertThat(amountUpdates.categoryName()).isNull();

            // ofName
            CashFlowHttpClient.CashChangeUpdates nameUpdates = CashFlowHttpClient.CashChangeUpdates.ofName("Test Name");
            assertThat(nameUpdates.amount()).isNull();
            assertThat(nameUpdates.name()).isEqualTo("Test Name");
            assertThat(nameUpdates.categoryName()).isNull();

            // ofCategory
            CashFlowHttpClient.CashChangeUpdates categoryUpdates = CashFlowHttpClient.CashChangeUpdates.ofCategory(
                    new CategoryName("TestCategory")
            );
            assertThat(categoryUpdates.amount()).isNull();
            assertThat(categoryUpdates.name()).isNull();
            assertThat(categoryUpdates.categoryName()).isEqualTo(new CategoryName("TestCategory"));
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldSendCorrectRequestBodyWithAllFields() throws Exception {
            // GIVEN
            List<CashChangeId> cashChangeIds = List.of(CashChangeId.of("CC1000000001"));

            CashFlowHttpClient.CashChangeUpdates updates = new CashFlowHttpClient.CashChangeUpdates(
                    Money.of(999, "PLN"),
                    "New Name",
                    new CategoryName("NewCat")
            );

            Map<String, Object> response = Map.of("updatedCount", 1, "skippedCount", 0);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);

            when(restTemplate.exchange(
                    anyString(),
                    eq(HttpMethod.PATCH),
                    entityCaptor.capture(),
                    eq(Map.class)
            )).thenReturn(new ResponseEntity<>(response, HttpStatus.OK));

            // WHEN
            cashFlowHttpClient.batchUpdateExpectedCashChanges(
                    CASH_FLOW_ID, RULE_ID, cashChangeIds, updates, AUTH_TOKEN
            );

            // THEN
            HttpEntity<Map<String, Object>> capturedEntity = entityCaptor.getValue();
            Map<String, Object> requestBody = capturedEntity.getBody();

            assertThat(requestBody.get("sourceRuleId")).isEqualTo("RR00000001");
            assertThat((List<String>) requestBody.get("cashChangeIds")).containsExactly("CC1000000001");

            Map<String, Object> updatesMap = (Map<String, Object>) requestBody.get("updates");
            assertThat(updatesMap).containsKey("amount");
            assertThat(updatesMap.get("name")).isEqualTo("New Name");
            assertThat(updatesMap.get("categoryName")).isEqualTo("NewCat");
        }
    }
}
