package com.multi.vidulum.recurring_rules.infrastructure;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    @Test
    void shouldExtractNestedCategoriesFromCashFlowResponse() throws Exception {
        // GIVEN: CashFlow response with nested categories (Health -> Medicine, Doctor)
        Map<String, Object> medicineCategory = Map.of(
                "categoryName", Map.of("name", "Medicine"),
                "nodes", List.of()
        );
        Map<String, Object> doctorCategory = Map.of(
                "categoryName", Map.of("name", "Doctor"),
                "nodes", List.of()
        );
        Map<String, Object> healthCategory = Map.of(
                "categoryName", Map.of("name", "Health"),
                "nodes", List.of(medicineCategory, doctorCategory)
        );

        Map<String, Object> salaryCategory = Map.of(
                "categoryName", Map.of("name", "Salary"),
                "nodes", List.of()
        );

        Map<String, Object> cashFlowResponse = Map.of(
                "cashFlowId", CASH_FLOW_ID.id(),
                "inflowCategories", List.of(salaryCategory),
                "outflowCategories", List.of(healthCategory)
        );

        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(Map.class)
        )).thenReturn(new ResponseEntity<>(cashFlowResponse, HttpStatus.OK));

        // WHEN: Get CashFlow info
        CashFlowHttpClient.CashFlowInfo info = cashFlowHttpClient.getCashFlowInfo(CASH_FLOW_ID, AUTH_TOKEN);

        // THEN: All categories including nested ones should be extracted
        assertThat(info.cashFlowId()).isEqualTo(CASH_FLOW_ID);

        assertThat(info.inflowCategories())
                .extracting(CategoryName::name)
                .containsExactly("Salary");

        // Critical assertion: nested categories (Medicine, Doctor) must be included
        assertThat(info.outflowCategories())
                .extracting(CategoryName::name)
                .containsExactlyInAnyOrder("Health", "Medicine", "Doctor");
    }

    @Test
    void shouldExtractDeeplyNestedCategories() throws Exception {
        // GIVEN: 3-level nested structure: Bills -> Utilities -> Electricity, Gas
        Map<String, Object> electricityCategory = Map.of(
                "categoryName", Map.of("name", "Electricity"),
                "nodes", List.of()
        );
        Map<String, Object> gasCategory = Map.of(
                "categoryName", Map.of("name", "Gas"),
                "nodes", List.of()
        );
        Map<String, Object> utilitiesCategory = Map.of(
                "categoryName", Map.of("name", "Utilities"),
                "nodes", List.of(electricityCategory, gasCategory)
        );
        Map<String, Object> billsCategory = Map.of(
                "categoryName", Map.of("name", "Bills"),
                "nodes", List.of(utilitiesCategory)
        );

        Map<String, Object> cashFlowResponse = Map.of(
                "cashFlowId", CASH_FLOW_ID.id(),
                "inflowCategories", List.of(),
                "outflowCategories", List.of(billsCategory)
        );

        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(Map.class)
        )).thenReturn(new ResponseEntity<>(cashFlowResponse, HttpStatus.OK));

        // WHEN: Get CashFlow info
        CashFlowHttpClient.CashFlowInfo info = cashFlowHttpClient.getCashFlowInfo(CASH_FLOW_ID, AUTH_TOKEN);

        // THEN: All levels of nested categories should be extracted
        assertThat(info.outflowCategories())
                .extracting(CategoryName::name)
                .containsExactlyInAnyOrder("Bills", "Utilities", "Electricity", "Gas");
    }

    @Test
    void shouldValidateCategoryExistsForNestedCategory() throws Exception {
        // GIVEN: Nested category structure
        Map<String, Object> medicineCategory = Map.of(
                "categoryName", Map.of("name", "Medicine"),
                "nodes", List.of()
        );
        Map<String, Object> healthCategory = Map.of(
                "categoryName", Map.of("name", "Health"),
                "nodes", List.of(medicineCategory)
        );

        Map<String, Object> cashFlowResponse = Map.of(
                "cashFlowId", CASH_FLOW_ID.id(),
                "inflowCategories", List.of(),
                "outflowCategories", List.of(healthCategory)
        );

        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(Map.class)
        )).thenReturn(new ResponseEntity<>(cashFlowResponse, HttpStatus.OK));

        // WHEN & THEN: Validation should pass for nested category "Medicine"
        // This would throw CategoryNotFoundException before the fix
        cashFlowHttpClient.validateCategoryExists(
                CASH_FLOW_ID,
                new CategoryName("Medicine"),
                false, // outflow
                AUTH_TOKEN
        );
        // No exception = test passed
    }

    @Test
    void shouldHandleEmptyNodesGracefully() throws Exception {
        // GIVEN: Categories with null or missing nodes
        Map<String, Object> simpleCategory = Map.of(
                "categoryName", Map.of("name", "Food")
                // No "nodes" field
        );

        Map<String, Object> cashFlowResponse = Map.of(
                "cashFlowId", CASH_FLOW_ID.id(),
                "inflowCategories", List.of(),
                "outflowCategories", List.of(simpleCategory)
        );

        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(Map.class)
        )).thenReturn(new ResponseEntity<>(cashFlowResponse, HttpStatus.OK));

        // WHEN: Get CashFlow info
        CashFlowHttpClient.CashFlowInfo info = cashFlowHttpClient.getCashFlowInfo(CASH_FLOW_ID, AUTH_TOKEN);

        // THEN: Should handle gracefully without NPE
        assertThat(info.outflowCategories())
                .extracting(CategoryName::name)
                .containsExactly("Food");
    }

    @Test
    void shouldHandleMultipleTopLevelCategoriesWithMixedNesting() throws Exception {
        // GIVEN: Multiple top-level categories, some with nesting, some without
        Map<String, Object> coffeeCategory = Map.of(
                "categoryName", Map.of("name", "Coffee"),
                "nodes", List.of()
        );
        Map<String, Object> foodCategory = Map.of(
                "categoryName", Map.of("name", "Food"),
                "nodes", List.of(coffeeCategory)
        );
        Map<String, Object> transportCategory = Map.of(
                "categoryName", Map.of("name", "Transport"),
                "nodes", List.of()
        );
        Map<String, Object> rentCategory = Map.of(
                "categoryName", Map.of("name", "Rent")
                // No nodes field
        );

        Map<String, Object> cashFlowResponse = Map.of(
                "cashFlowId", CASH_FLOW_ID.id(),
                "inflowCategories", List.of(),
                "outflowCategories", List.of(foodCategory, transportCategory, rentCategory)
        );

        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(Map.class)
        )).thenReturn(new ResponseEntity<>(cashFlowResponse, HttpStatus.OK));

        // WHEN: Get CashFlow info
        CashFlowHttpClient.CashFlowInfo info = cashFlowHttpClient.getCashFlowInfo(CASH_FLOW_ID, AUTH_TOKEN);

        // THEN: All categories should be extracted correctly
        assertThat(info.outflowCategories())
                .extracting(CategoryName::name)
                .containsExactlyInAnyOrder("Food", "Coffee", "Transport", "Rent");
    }
}
