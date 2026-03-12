package com.multi.vidulum.cashflow.app;

import com.multi.vidulum.AuthenticatedHttpIntegrationTest;
import com.multi.vidulum.cashflow.domain.CashFlow;
import com.multi.vidulum.cashflow.domain.CashFlowEvent;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.Category;
import com.multi.vidulum.cashflow.domain.DomainCashFlowRepository;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastStatement;
import com.multi.vidulum.cashflow_forecast_processor.app.CashFlowForecastStatementRepository;
import com.multi.vidulum.cashflow_forecast_processor.app.CategoryNode;
import com.multi.vidulum.cashflow_forecast_processor.app.CurrentCategoryStructure;
import com.multi.vidulum.cashflow_forecast_processor.infrastructure.CashFlowForecastEntity;
import com.multi.vidulum.cashflow_forecast_processor.infrastructure.CashFlowForecastMongoRepository;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.error.ApiError;
import com.multi.vidulum.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for the Move Category functionality.
 * <p>
 * Tests cover:
 * - Moving categories within the same hierarchy level (root to root parent)
 * - Moving categories from root to subcategory
 * - Moving subcategories to root
 * - Moving categories with nested subcategories (subtree moves together)
 * - Validation errors (circular dependency, system category, same parent, not found)
 * - Forecast processing (categories properly updated in forecast statement)
 */
@Slf4j
public class MoveCategoryIntegrationTest extends AuthenticatedHttpIntegrationTest {

    private static final AtomicInteger NAME_COUNTER = new AtomicInteger(0);
    private static final ZonedDateTime FIXED_NOW = ZonedDateTime.parse("2022-01-01T00:00:00Z[UTC]");

    @Autowired
    private CashFlowRestController cashFlowRestController;

    @Autowired
    private DomainCashFlowRepository domainCashFlowRepository;

    @Autowired
    private CashFlowForecastMongoRepository cashFlowForecastMongoRepository;

    @Autowired
    private CashFlowForecastStatementRepository statementRepository;

    @Autowired
    private Clock clock;

    private CashFlowHttpActor actor;

    private CashFlowHttpActor createActor() {
        // Register a unique user for each test and get JWT token
        registerAndAuthenticate();
        CashFlowHttpActor newActor = new CashFlowHttpActor(restTemplate, port);
        newActor.setJwtToken(accessToken);
        return newActor;
    }

    private String uniqueCashFlowName() {
        return "MoveCatCF-" + NAME_COUNTER.incrementAndGet();
    }

    // ============ Success Cases ============

    @Test
    void shouldMoveCategoryFromRootToAnotherRootCategory() {
        // Given: CashFlow with two root categories: "Bills" and "Housing"
        actor = createActor();
        YearMonth startPeriod = YearMonth.now(clock).minusMonths(2);

        String cashFlowId = actor.createCashFlowWithHistory(userId, uniqueCashFlowName(), startPeriod, Money.of(5000, "PLN"));
        CashFlowId cfId = CashFlowId.of(cashFlowId);

        // Create categories
        actor.createCategory(cashFlowId, "Bills", Type.OUTFLOW);
        actor.createCategory(cashFlowId, "Housing", Type.OUTFLOW);

        // Attest to move to OPEN mode
        Money confirmedBalance = Money.of(5000, "PLN");
        actor.attestHistoricalImport(cashFlowId, confirmedBalance, false, false);

        // Wait for events to be processed
        waitForEventProcessed(cashFlowId, CashFlowEvent.HistoricalImportAttestedEvent.class.getSimpleName());

        // When: Move "Bills" to be a subcategory of "Housing"
        actor.moveCategory(cashFlowId, "Bills", "Housing", Type.OUTFLOW);

        // Wait for CategoryMovedEvent to be processed
        waitForEventProcessed(cashFlowId, CashFlowEvent.CategoryMovedEvent.class.getSimpleName());

        // Then: Verify in domain model
        CashFlow cashFlow = domainCashFlowRepository.findById(cfId).orElseThrow();
        List<Category> outflowCategories = cashFlow.getSnapshot().outflowCategories();

        // "Bills" should no longer be at root level
        assertThat(outflowCategories.stream().map(c -> c.getCategoryName().name()))
                .doesNotContain("Bills");

        // "Bills" should be a subcategory of "Housing"
        Category housing = outflowCategories.stream()
                .filter(c -> c.getCategoryName().name().equals("Housing"))
                .findFirst()
                .orElseThrow();
        assertThat(housing.getSubCategories()).hasSize(1);
        assertThat(housing.getSubCategories().get(0).getCategoryName().name()).isEqualTo("Bills");

        // Verify in forecast statement
        await().atMost(10, SECONDS).until(() ->
                statementRepository.findByCashFlowId(cfId).isPresent());

        CashFlowForecastStatement statement = statementRepository.findByCashFlowId(cfId).orElseThrow();
        CurrentCategoryStructure structure = statement.getCategoryStructure();

        // Check structure: Housing should have Bills as child
        CategoryNode housingNode = structure.outflowCategoryStructure().stream()
                .filter(n -> n.getCategoryName().name().equals("Housing"))
                .findFirst()
                .orElseThrow();
        assertThat(housingNode.getNodes()).hasSize(1);
        assertThat(housingNode.getNodes().get(0).getCategoryName().name()).isEqualTo("Bills");

        log.info("Successfully moved category 'Bills' under 'Housing'");
    }

    @Test
    void shouldMoveCategoryFromSubcategoryToRoot() {
        // Given: CashFlow with "Housing" category that has "Bills" as subcategory
        actor = createActor();
        YearMonth startPeriod = YearMonth.now(clock).minusMonths(2);

        String cashFlowId = actor.createCashFlowWithHistory(userId, uniqueCashFlowName(), startPeriod, Money.of(5000, "PLN"));
        CashFlowId cfId = CashFlowId.of(cashFlowId);

        // Create categories with hierarchy
        actor.createCategory(cashFlowId, "Housing", Type.OUTFLOW);
        actor.createSubcategory(cashFlowId, "Housing", "Bills", Type.OUTFLOW);

        // Attest
        actor.attestHistoricalImport(cashFlowId, Money.of(5000, "PLN"), false, false);
        waitForEventProcessed(cashFlowId, CashFlowEvent.HistoricalImportAttestedEvent.class.getSimpleName());

        // When: Move "Bills" to root level (null parent)
        actor.moveCategory(cashFlowId, "Bills", null, Type.OUTFLOW);
        waitForEventProcessed(cashFlowId, CashFlowEvent.CategoryMovedEvent.class.getSimpleName());

        // Then: Verify "Bills" is now at root level
        CashFlow cashFlow = domainCashFlowRepository.findById(cfId).orElseThrow();
        List<Category> outflowCategories = cashFlow.getSnapshot().outflowCategories();

        assertThat(outflowCategories.stream().map(c -> c.getCategoryName().name()))
                .contains("Bills", "Housing", "Uncategorized");

        // Housing should have no subcategories
        Category housing = outflowCategories.stream()
                .filter(c -> c.getCategoryName().name().equals("Housing"))
                .findFirst()
                .orElseThrow();
        assertThat(housing.getSubCategories()).isEmpty();

        log.info("Successfully moved category 'Bills' to root level");
    }

    @Test
    void shouldMoveCategoryWithNestedSubcategories() {
        // Given: CashFlow with nested hierarchy: "Housing" -> "Bills" -> "Electricity"
        actor = createActor();
        YearMonth startPeriod = YearMonth.now(clock).minusMonths(2);

        String cashFlowId = actor.createCashFlowWithHistory(userId, uniqueCashFlowName(), startPeriod, Money.of(5000, "PLN"));
        CashFlowId cfId = CashFlowId.of(cashFlowId);

        // Create hierarchy
        actor.createCategory(cashFlowId, "Housing", Type.OUTFLOW);
        actor.createSubcategory(cashFlowId, "Housing", "Bills", Type.OUTFLOW);
        actor.createSubcategory(cashFlowId, "Bills", "Electricity", Type.OUTFLOW);
        actor.createCategory(cashFlowId, "Other", Type.OUTFLOW);

        // Attest
        actor.attestHistoricalImport(cashFlowId, Money.of(5000, "PLN"), false, false);
        waitForEventProcessed(cashFlowId, CashFlowEvent.HistoricalImportAttestedEvent.class.getSimpleName());

        // When: Move "Bills" (with "Electricity" subtree) to "Other"
        actor.moveCategory(cashFlowId, "Bills", "Other", Type.OUTFLOW);
        waitForEventProcessed(cashFlowId, CashFlowEvent.CategoryMovedEvent.class.getSimpleName());

        // Then: Verify entire subtree moved
        CashFlow cashFlow = domainCashFlowRepository.findById(cfId).orElseThrow();
        List<Category> outflowCategories = cashFlow.getSnapshot().outflowCategories();

        // "Other" should have "Bills" which has "Electricity"
        Category other = outflowCategories.stream()
                .filter(c -> c.getCategoryName().name().equals("Other"))
                .findFirst()
                .orElseThrow();
        assertThat(other.getSubCategories()).hasSize(1);
        Category bills = other.getSubCategories().get(0);
        assertThat(bills.getCategoryName().name()).isEqualTo("Bills");
        assertThat(bills.getSubCategories()).hasSize(1);
        assertThat(bills.getSubCategories().get(0).getCategoryName().name()).isEqualTo("Electricity");

        // "Housing" should have no subcategories
        Category housing = outflowCategories.stream()
                .filter(c -> c.getCategoryName().name().equals("Housing"))
                .findFirst()
                .orElseThrow();
        assertThat(housing.getSubCategories()).isEmpty();

        log.info("Successfully moved category subtree 'Bills->Electricity' under 'Other'");
    }

    // ============ Error Cases ============

    @Test
    void shouldRejectMovingSystemCategory() {
        // Given: CashFlow with system "Uncategorized" category
        actor = createActor();
        YearMonth startPeriod = YearMonth.now(clock).minusMonths(2);

        String cashFlowId = actor.createCashFlowWithHistory(userId, uniqueCashFlowName(), startPeriod, Money.of(5000, "PLN"));

        actor.createCategory(cashFlowId, "Housing", Type.OUTFLOW);
        actor.attestHistoricalImport(cashFlowId, Money.of(5000, "PLN"), false, false);
        waitForEventProcessed(cashFlowId, CashFlowEvent.HistoricalImportAttestedEvent.class.getSimpleName());

        // When: Try to move "Uncategorized" (system category)
        ResponseEntity<ApiError> response = actor.moveCategoryExpectingError(
                cashFlowId, "Uncategorized", "Housing", Type.OUTFLOW);

        // Then: Should get CANNOT_MOVE_SYSTEM_CATEGORY error
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.CANNOT_MOVE_SYSTEM_CATEGORY.name());

        log.info("Correctly rejected moving system category");
    }

    @Test
    void shouldRejectCircularDependency() {
        // Given: CashFlow with "Parent" -> "Child" hierarchy
        actor = createActor();
        YearMonth startPeriod = YearMonth.now(clock).minusMonths(2);

        String cashFlowId = actor.createCashFlowWithHistory(userId, uniqueCashFlowName(), startPeriod, Money.of(5000, "PLN"));

        actor.createCategory(cashFlowId, "Parent", Type.OUTFLOW);
        actor.createSubcategory(cashFlowId, "Parent", "Child", Type.OUTFLOW);
        actor.attestHistoricalImport(cashFlowId, Money.of(5000, "PLN"), false, false);
        waitForEventProcessed(cashFlowId, CashFlowEvent.HistoricalImportAttestedEvent.class.getSimpleName());

        // When: Try to move "Parent" under "Child" (circular dependency)
        ResponseEntity<ApiError> response = actor.moveCategoryExpectingError(
                cashFlowId, "Parent", "Child", Type.OUTFLOW);

        // Then: Should get CATEGORY_CIRCULAR_DEPENDENCY error
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.CATEGORY_CIRCULAR_DEPENDENCY.name());

        log.info("Correctly rejected circular dependency");
    }

    @Test
    void shouldRejectMoveToSameParent() {
        // Given: CashFlow with "Parent" -> "Child" hierarchy
        actor = createActor();
        YearMonth startPeriod = YearMonth.now(clock).minusMonths(2);

        String cashFlowId = actor.createCashFlowWithHistory(userId, uniqueCashFlowName(), startPeriod, Money.of(5000, "PLN"));

        actor.createCategory(cashFlowId, "Parent", Type.OUTFLOW);
        actor.createSubcategory(cashFlowId, "Parent", "Child", Type.OUTFLOW);
        actor.attestHistoricalImport(cashFlowId, Money.of(5000, "PLN"), false, false);
        waitForEventProcessed(cashFlowId, CashFlowEvent.HistoricalImportAttestedEvent.class.getSimpleName());

        // When: Try to move "Child" to same parent "Parent" (no-op)
        ResponseEntity<ApiError> response = actor.moveCategoryExpectingError(
                cashFlowId, "Child", "Parent", Type.OUTFLOW);

        // Then: Should get CATEGORY_MOVE_TO_SAME_PARENT error
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.CATEGORY_MOVE_TO_SAME_PARENT.name());

        log.info("Correctly rejected move to same parent");
    }

    @Test
    void shouldRejectMovingNonExistentCategory() {
        // Given: CashFlow without "NonExistent" category
        actor = createActor();
        YearMonth startPeriod = YearMonth.now(clock).minusMonths(2);

        String cashFlowId = actor.createCashFlowWithHistory(userId, uniqueCashFlowName(), startPeriod, Money.of(5000, "PLN"));

        actor.attestHistoricalImport(cashFlowId, Money.of(5000, "PLN"), false, false);
        waitForEventProcessed(cashFlowId, CashFlowEvent.HistoricalImportAttestedEvent.class.getSimpleName());

        // When: Try to move non-existent category
        ResponseEntity<ApiError> response = actor.moveCategoryExpectingError(
                cashFlowId, "NonExistent", null, Type.OUTFLOW);

        // Then: Should get CATEGORY_NOT_FOUND error
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.CATEGORY_NOT_FOUND.name());

        log.info("Correctly rejected moving non-existent category");
    }

    @Test
    void shouldRejectMovingToNonExistentParent() {
        // Given: CashFlow with "Bills" category but no "NonExistent" parent
        actor = createActor();
        YearMonth startPeriod = YearMonth.now(clock).minusMonths(2);

        String cashFlowId = actor.createCashFlowWithHistory(userId, uniqueCashFlowName(), startPeriod, Money.of(5000, "PLN"));

        actor.createCategory(cashFlowId, "Bills", Type.OUTFLOW);
        actor.attestHistoricalImport(cashFlowId, Money.of(5000, "PLN"), false, false);
        waitForEventProcessed(cashFlowId, CashFlowEvent.HistoricalImportAttestedEvent.class.getSimpleName());

        // When: Try to move to non-existent parent
        ResponseEntity<ApiError> response = actor.moveCategoryExpectingError(
                cashFlowId, "Bills", "NonExistent", Type.OUTFLOW);

        // Then: Should get CATEGORY_NOT_FOUND error
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.CATEGORY_NOT_FOUND.name());

        log.info("Correctly rejected moving to non-existent parent");
    }

    @Test
    void shouldRejectMovingCategoryWithWrongType() {
        // Given: CashFlow with "Salary" as INFLOW category
        actor = createActor();
        YearMonth startPeriod = YearMonth.now(clock).minusMonths(2);

        String cashFlowId = actor.createCashFlowWithHistory(userId, uniqueCashFlowName(), startPeriod, Money.of(5000, "PLN"));

        // Create an INFLOW category
        actor.createCategory(cashFlowId, "Salary", Type.INFLOW);
        actor.createCategory(cashFlowId, "Bills", Type.OUTFLOW);
        actor.attestHistoricalImport(cashFlowId, Money.of(5000, "PLN"), false, false);
        waitForEventProcessed(cashFlowId, CashFlowEvent.HistoricalImportAttestedEvent.class.getSimpleName());

        // When: Try to move "Salary" (INFLOW) but specify OUTFLOW type - attempting to change type
        ResponseEntity<ApiError> response = actor.moveCategoryExpectingError(
                cashFlowId, "Salary", "Bills", Type.OUTFLOW);

        // Then: Should get CANNOT_CHANGE_CATEGORY_TYPE error (not generic NOT_FOUND)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.CANNOT_CHANGE_CATEGORY_TYPE.name());
        assertThat(response.getBody().message()).contains("INFLOW");
        assertThat(response.getBody().message()).contains("OUTFLOW");

        log.info("Correctly rejected moving category with wrong type specified");
    }

    // ============ Position/Reorder Cases ============

    @Test
    void shouldReorderCategoryWithinSameParentUsingPosition() {
        // Given: CashFlow with three root OUTFLOW categories: "Bills", "Food", "Transport"
        actor = createActor();
        YearMonth startPeriod = YearMonth.now(clock).minusMonths(2);

        String cashFlowId = actor.createCashFlowWithHistory(userId, uniqueCashFlowName(), startPeriod, Money.of(5000, "PLN"));
        CashFlowId cfId = CashFlowId.of(cashFlowId);

        actor.createCategory(cashFlowId, "Bills", Type.OUTFLOW);
        actor.createCategory(cashFlowId, "Food", Type.OUTFLOW);
        actor.createCategory(cashFlowId, "Transport", Type.OUTFLOW);

        actor.attestHistoricalImport(cashFlowId, Money.of(5000, "PLN"), false, false);
        waitForEventProcessed(cashFlowId, CashFlowEvent.HistoricalImportAttestedEvent.class.getSimpleName());

        // Initial order (after Uncategorized): [Uncategorized, Bills, Food, Transport]
        CashFlow before = domainCashFlowRepository.findById(cfId).orElseThrow();
        List<String> orderBefore = before.getSnapshot().outflowCategories().stream()
                .map(c -> c.getCategoryName().name()).toList();
        log.info("Order before reorder: {}", orderBefore);

        // When: Move "Transport" to position 0 (first position among siblings, same parent = root)
        actor.moveCategory(cashFlowId, "Transport", null, Type.OUTFLOW, 0);
        waitForEventProcessed(cashFlowId, CashFlowEvent.CategoryMovedEvent.class.getSimpleName());

        // Then: "Transport" should be at position 0
        CashFlow after = domainCashFlowRepository.findById(cfId).orElseThrow();
        List<String> orderAfter = after.getSnapshot().outflowCategories().stream()
                .map(c -> c.getCategoryName().name()).toList();
        log.info("Order after reorder: {}", orderAfter);

        // Expected: [Transport, Uncategorized, Bills, Food]
        assertThat(orderAfter.get(0)).isEqualTo("Transport");
        assertThat(orderAfter).containsExactly("Transport", "Uncategorized", "Bills", "Food");

        log.info("Successfully reordered category 'Transport' to position 0");
    }

    @Test
    void shouldMoveAndPositionCategoryInNewParent() {
        // Given: CashFlow with "Housing" root and "Bills", "Food" root categories
        actor = createActor();
        YearMonth startPeriod = YearMonth.now(clock).minusMonths(2);

        String cashFlowId = actor.createCashFlowWithHistory(userId, uniqueCashFlowName(), startPeriod, Money.of(5000, "PLN"));
        CashFlowId cfId = CashFlowId.of(cashFlowId);

        actor.createCategory(cashFlowId, "Housing", Type.OUTFLOW);
        actor.createSubcategory(cashFlowId, "Housing", "Rent", Type.OUTFLOW);
        actor.createSubcategory(cashFlowId, "Housing", "Utilities", Type.OUTFLOW);
        actor.createCategory(cashFlowId, "Bills", Type.OUTFLOW);

        actor.attestHistoricalImport(cashFlowId, Money.of(5000, "PLN"), false, false);
        waitForEventProcessed(cashFlowId, CashFlowEvent.HistoricalImportAttestedEvent.class.getSimpleName());

        // When: Move "Bills" to "Housing" at position 0 (before Rent)
        actor.moveCategory(cashFlowId, "Bills", "Housing", Type.OUTFLOW, 0);
        waitForEventProcessed(cashFlowId, CashFlowEvent.CategoryMovedEvent.class.getSimpleName());

        // Then: "Bills" should be first child of "Housing"
        CashFlow after = domainCashFlowRepository.findById(cfId).orElseThrow();
        Category housing = after.getSnapshot().outflowCategories().stream()
                .filter(c -> c.getCategoryName().name().equals("Housing"))
                .findFirst()
                .orElseThrow();

        List<String> childNames = housing.getSubCategories().stream()
                .map(c -> c.getCategoryName().name()).toList();
        assertThat(childNames).containsExactly("Bills", "Rent", "Utilities");

        log.info("Successfully moved 'Bills' to 'Housing' at position 0");
    }

    @Test
    void shouldAllowReorderWithSameParentAndPositionProvided() {
        // Given: CashFlow with "Parent" -> ["Child1", "Child2", "Child3"]
        actor = createActor();
        YearMonth startPeriod = YearMonth.now(clock).minusMonths(2);

        String cashFlowId = actor.createCashFlowWithHistory(userId, uniqueCashFlowName(), startPeriod, Money.of(5000, "PLN"));
        CashFlowId cfId = CashFlowId.of(cashFlowId);

        actor.createCategory(cashFlowId, "Parent", Type.OUTFLOW);
        actor.createSubcategory(cashFlowId, "Parent", "Child1", Type.OUTFLOW);
        actor.createSubcategory(cashFlowId, "Parent", "Child2", Type.OUTFLOW);
        actor.createSubcategory(cashFlowId, "Parent", "Child3", Type.OUTFLOW);

        actor.attestHistoricalImport(cashFlowId, Money.of(5000, "PLN"), false, false);
        waitForEventProcessed(cashFlowId, CashFlowEvent.HistoricalImportAttestedEvent.class.getSimpleName());

        // When: Move "Child3" to position 0 within same parent "Parent"
        // This should work because position is provided (reorder use case)
        actor.moveCategory(cashFlowId, "Child3", "Parent", Type.OUTFLOW, 0);
        waitForEventProcessed(cashFlowId, CashFlowEvent.CategoryMovedEvent.class.getSimpleName());

        // Then: "Child3" should be first
        CashFlow after = domainCashFlowRepository.findById(cfId).orElseThrow();
        Category parent = after.getSnapshot().outflowCategories().stream()
                .filter(c -> c.getCategoryName().name().equals("Parent"))
                .findFirst()
                .orElseThrow();

        List<String> childNames = parent.getSubCategories().stream()
                .map(c -> c.getCategoryName().name()).toList();
        assertThat(childNames).containsExactly("Child3", "Child1", "Child2");

        log.info("Successfully reordered 'Child3' to position 0 within same parent");
    }

    @Test
    void shouldHandlePositionLargerThanListSize() {
        // Given: CashFlow with two root OUTFLOW categories
        actor = createActor();
        YearMonth startPeriod = YearMonth.now(clock).minusMonths(2);

        String cashFlowId = actor.createCashFlowWithHistory(userId, uniqueCashFlowName(), startPeriod, Money.of(5000, "PLN"));
        CashFlowId cfId = CashFlowId.of(cashFlowId);

        actor.createCategory(cashFlowId, "First", Type.OUTFLOW);
        actor.createCategory(cashFlowId, "Second", Type.OUTFLOW);

        actor.attestHistoricalImport(cashFlowId, Money.of(5000, "PLN"), false, false);
        waitForEventProcessed(cashFlowId, CashFlowEvent.HistoricalImportAttestedEvent.class.getSimpleName());

        // When: Move "First" with position=999 (larger than list size) - should append at end
        actor.moveCategory(cashFlowId, "First", null, Type.OUTFLOW, 999);
        waitForEventProcessed(cashFlowId, CashFlowEvent.CategoryMovedEvent.class.getSimpleName());

        // Then: "First" should be at the end
        CashFlow after = domainCashFlowRepository.findById(cfId).orElseThrow();
        List<String> orderAfter = after.getSnapshot().outflowCategories().stream()
                .map(c -> c.getCategoryName().name()).toList();

        // Should be: [Uncategorized, Second, First] - "First" at end
        assertThat(orderAfter.get(orderAfter.size() - 1)).isEqualTo("First");

        log.info("Successfully handled position larger than list size");
    }

    // ============ Helper Methods ============

    private void waitForEventProcessed(String cashFlowId, String eventTypeName) {
        await().atMost(10, SECONDS).until(() ->
                cashFlowForecastMongoRepository.findByCashFlowId(cashFlowId)
                        .map(entity -> entity.getEvents().stream()
                                .map(CashFlowForecastEntity.Processing::type)
                                .toList()
                                .contains(eventTypeName))
                        .orElse(false));
    }
}
