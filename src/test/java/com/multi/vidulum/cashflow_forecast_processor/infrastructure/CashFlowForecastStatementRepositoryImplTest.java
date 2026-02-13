package com.multi.vidulum.cashflow_forecast_processor.infrastructure;

import com.multi.vidulum.TestIds;
import com.multi.vidulum.cashflow.domain.BankAccountNumber;
import com.multi.vidulum.cashflow.domain.CashChangeId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.CategoryOrigin;
import com.multi.vidulum.cashflow.domain.Name;
import com.multi.vidulum.cashflow_forecast_processor.app.*;
import com.multi.vidulum.cashflow_forecast_processor.infrastructure.entity.CashFlowForecastStatementEntity;
import com.multi.vidulum.common.Checksum;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.trading.domain.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for CashFlowForecastStatementRepositoryImpl with MongoDB.
 * Tests persistence and retrieval of CashFlowForecastStatement with nested structures.
 */
public class CashFlowForecastStatementRepositoryImplTest extends IntegrationTest {

    @Autowired
    private CashFlowForecastStatementRepositoryImpl repository;

    @Autowired
    private CashFlowForecastStatementMongoRepository mongoRepository;

    private static final ZonedDateTime FIXED_NOW = ZonedDateTime.parse("2022-01-01T00:00:00Z[UTC]");
    private static final String TEST_CURRENCY = "PLN";

    @BeforeEach
    public void setUp() {
        mongoRepository.deleteAll();
    }

    @Test
    void shouldSaveAndRetrieveSimpleForecastStatement() {
        // given
        CashFlowId cashFlowId = TestIds.nextCashFlowId();

        CashFlowForecastStatement statement = createSimpleForecastStatement(cashFlowId);

        // when
        repository.save(statement);

        // then
        Optional<CashFlowForecastStatement> retrieved = repository.findByCashFlowId(cashFlowId);

        assertThat(retrieved).isPresent();
        CashFlowForecastStatement result = retrieved.get();

        assertThat(result.getCashFlowId()).isEqualTo(cashFlowId);
        assertThat(result.getBankAccountNumber().iban().value()).isEqualTo("PL61109010140000071219812874");
        assertThat(result.getBankAccountNumber().denomination().getId()).isEqualTo(TEST_CURRENCY);
        assertThat(result.getLastMessageChecksum().checksum()).isEqualTo("test-checksum-123");
        assertThat(result.getForecasts()).hasSize(3);
    }

    @Test
    void shouldSaveAndRetrieveForecastStatementWithNestedCategories() {
        // given
        CashFlowId cashFlowId = TestIds.nextCashFlowId();

        CashFlowForecastStatement statement = createForecastStatementWithNestedCategories(cashFlowId);

        // when
        repository.save(statement);

        // then
        Optional<CashFlowForecastStatement> retrieved = repository.findByCashFlowId(cashFlowId);

        assertThat(retrieved).isPresent();
        CashFlowForecastStatement result = retrieved.get();

        // Verify nested category structure in monthly forecast
        CashFlowMonthlyForecast activeForecast = result.getForecasts().get(YearMonth.of(2022, 1));
        assertThat(activeForecast).isNotNull();
        assertThat(activeForecast.getStatus()).isEqualTo(CashFlowMonthlyForecast.Status.ACTIVE);

        // Check inflow categories with nested structure
        List<CashCategory> inflowCategories = activeForecast.getCategorizedInFlows();
        assertThat(inflowCategories).hasSize(2);

        // Find Salary category with Bonus subcategory
        CashCategory salaryCategory = inflowCategories.stream()
                .filter(c -> "Salary".equals(c.getCategoryName().name()))
                .findFirst()
                .orElseThrow();

        assertThat(salaryCategory.getSubCategories()).hasSize(1);
        assertThat(salaryCategory.getSubCategories().get(0).getCategoryName().name()).isEqualTo("Bonus");
        assertThat(salaryCategory.getOrigin()).isEqualTo(CategoryOrigin.USER_CREATED);

        // Check outflow categories with nested structure
        List<CashCategory> outflowCategories = activeForecast.getCategorizedOutFlows();
        assertThat(outflowCategories).hasSize(2);

        CashCategory housingCategory = outflowCategories.stream()
                .filter(c -> "Housing".equals(c.getCategoryName().name()))
                .findFirst()
                .orElseThrow();

        assertThat(housingCategory.getSubCategories()).hasSize(1);
        assertThat(housingCategory.getSubCategories().get(0).getCategoryName().name()).isEqualTo("Utilities");
    }

    @Test
    void shouldSaveAndRetrieveForecastStatementWithTransactions() {
        // given
        CashFlowId cashFlowId = TestIds.nextCashFlowId();

        CashFlowForecastStatement statement = createForecastStatementWithTransactions(cashFlowId);

        // when
        repository.save(statement);

        // then
        Optional<CashFlowForecastStatement> retrieved = repository.findByCashFlowId(cashFlowId);

        assertThat(retrieved).isPresent();
        CashFlowForecastStatement result = retrieved.get();

        CashFlowMonthlyForecast activeForecast = result.getForecasts().get(YearMonth.of(2022, 1));
        CashCategory salaryCategory = activeForecast.getCategorizedInFlows().stream()
                .filter(c -> "Salary".equals(c.getCategoryName().name()))
                .findFirst()
                .orElseThrow();

        // Check PAID transactions
        List<TransactionDetails> paidTransactions = salaryCategory.getGroupedTransactions().get(PaymentStatus.PAID);
        assertThat(paidTransactions).hasSize(1);
        assertThat(paidTransactions.get(0).getName().name()).isEqualTo("January Salary");
        assertThat(paidTransactions.get(0).getMoney()).isEqualTo(Money.of(15000, TEST_CURRENCY));

        // Check EXPECTED transactions
        List<TransactionDetails> expectedTransactions = salaryCategory.getGroupedTransactions().get(PaymentStatus.EXPECTED);
        assertThat(expectedTransactions).hasSize(1);
        assertThat(expectedTransactions.get(0).getName().name()).isEqualTo("February Salary");
    }

    @Test
    void shouldSaveAndRetrieveForecastStatementWithCategoryStructure() {
        // given
        CashFlowId cashFlowId = TestIds.nextCashFlowId();

        CashFlowForecastStatement statement = createForecastStatementWithCategoryStructure(cashFlowId);

        // when
        repository.save(statement);

        // then
        Optional<CashFlowForecastStatement> retrieved = repository.findByCashFlowId(cashFlowId);

        assertThat(retrieved).isPresent();
        CashFlowForecastStatement result = retrieved.get();

        CurrentCategoryStructure categoryStructure = result.getCategoryStructure();
        assertThat(categoryStructure).isNotNull();

        // Check inflow category structure
        List<CategoryNode> inflowNodes = categoryStructure.inflowCategoryStructure();
        assertThat(inflowNodes).hasSize(2);

        CategoryNode salaryNode = inflowNodes.stream()
                .filter(n -> "Salary".equals(n.getCategoryName().name()))
                .findFirst()
                .orElseThrow();

        assertThat(salaryNode.getNodes()).hasSize(1);
        assertThat(salaryNode.getNodes().get(0).getCategoryName().name()).isEqualTo("Bonus");
        assertThat(salaryNode.getOrigin()).isEqualTo(CategoryOrigin.USER_CREATED);
    }

    @Test
    void shouldUpdateExistingForecastStatement() {
        // given
        CashFlowId cashFlowId = TestIds.nextCashFlowId();

        CashFlowForecastStatement statement = createSimpleForecastStatement(cashFlowId);
        repository.save(statement);

        // Modify statement
        statement.setLastMessageChecksum(new Checksum("updated-checksum-456"));
        CashFlowMonthlyForecast activeForecast = statement.getForecasts().get(YearMonth.of(2022, 1));
        activeForecast.getCashFlowStats().setEnd(Money.of(20000, TEST_CURRENCY));

        // when
        repository.save(statement);

        // then
        Optional<CashFlowForecastStatement> retrieved = repository.findByCashFlowId(cashFlowId);

        assertThat(retrieved).isPresent();
        CashFlowForecastStatement result = retrieved.get();

        assertThat(result.getLastMessageChecksum().checksum()).isEqualTo("updated-checksum-456");
        assertThat(result.getForecasts().get(YearMonth.of(2022, 1)).getCashFlowStats().getEnd())
                .isEqualTo(Money.of(20000, TEST_CURRENCY));

        // Verify only one document exists
        assertThat(mongoRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldReturnEmptyForNonExistentCashFlowId() {
        // given
        CashFlowId nonExistentId = TestIds.nextCashFlowId();

        // when
        Optional<CashFlowForecastStatement> result = repository.findByCashFlowId(nonExistentId);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    void shouldPersistAttestation() {
        // given
        CashFlowId cashFlowId = TestIds.nextCashFlowId();

        CashFlowForecastStatement statement = createForecastStatementWithAttestation(cashFlowId);

        // when
        repository.save(statement);

        // then
        Optional<CashFlowForecastStatement> retrieved = repository.findByCashFlowId(cashFlowId);

        assertThat(retrieved).isPresent();
        CashFlowForecastStatement result = retrieved.get();

        CashFlowMonthlyForecast importedForecast = result.getForecasts().get(YearMonth.of(2021, 12));
        assertThat(importedForecast.getStatus()).isEqualTo(CashFlowMonthlyForecast.Status.IMPORTED);
        assertThat(importedForecast.getAttestation()).isNotNull();
        assertThat(importedForecast.getAttestation().bankAccountBalance()).isEqualTo(Money.of(10000, TEST_CURRENCY));
        assertThat(importedForecast.getAttestation().type()).isEqualTo(Attestation.Type.MANUAL);
    }

    @Test
    void shouldPersistMultipleForecastStatements() {
        // given
        CashFlowId cashFlowId1 = TestIds.nextCashFlowId();
        CashFlowId cashFlowId2 = TestIds.nextCashFlowId();

        CashFlowForecastStatement statement1 = createSimpleForecastStatement(cashFlowId1);
        CashFlowForecastStatement statement2 = createSimpleForecastStatement(cashFlowId2);

        // when
        repository.save(statement1);
        repository.save(statement2);

        // then
        assertThat(mongoRepository.count()).isEqualTo(2);

        assertThat(repository.findByCashFlowId(cashFlowId1)).isPresent();
        assertThat(repository.findByCashFlowId(cashFlowId2)).isPresent();
    }

    // Helper methods

    private CashFlowForecastStatement createSimpleForecastStatement(CashFlowId cashFlowId) {
        Map<YearMonth, CashFlowMonthlyForecast> forecasts = new LinkedHashMap<>();

        // Add three months of forecasts
        forecasts.put(YearMonth.of(2021, 12), createMonthlyForecast(
                YearMonth.of(2021, 12), CashFlowMonthlyForecast.Status.IMPORTED));
        forecasts.put(YearMonth.of(2022, 1), createMonthlyForecast(
                YearMonth.of(2022, 1), CashFlowMonthlyForecast.Status.ACTIVE));
        forecasts.put(YearMonth.of(2022, 2), createMonthlyForecast(
                YearMonth.of(2022, 2), CashFlowMonthlyForecast.Status.FORECASTED));

        return CashFlowForecastStatement.builder()
                .cashFlowId(cashFlowId)
                .forecasts(forecasts)
                .bankAccountNumber(BankAccountNumber.fromIban("PL61109010140000071219812874", Currency.of(TEST_CURRENCY)))
                .categoryStructure(createSimpleCategoryStructure())
                .lastModification(FIXED_NOW)
                .lastMessageChecksum(new Checksum("test-checksum-123"))
                .build();
    }

    private CashFlowForecastStatement createForecastStatementWithNestedCategories(CashFlowId cashFlowId) {
        Map<YearMonth, CashFlowMonthlyForecast> forecasts = new LinkedHashMap<>();

        // Create monthly forecast with nested categories
        List<CashCategory> inflowCategories = new ArrayList<>();

        // Salary with Bonus subcategory
        CashCategory bonusCategory = CashCategory.builder()
                .categoryName(new CategoryName("Bonus"))
                .category(new Category("Bonus"))
                .subCategories(new LinkedList<>())
                .groupedTransactions(new GroupedTransactions())
                .totalPaidValue(Money.zero(TEST_CURRENCY))
                .origin(CategoryOrigin.USER_CREATED)
                .build();

        CashCategory salaryCategory = CashCategory.builder()
                .categoryName(new CategoryName("Salary"))
                .category(new Category("Salary"))
                .subCategories(new LinkedList<>(List.of(bonusCategory)))
                .groupedTransactions(new GroupedTransactions())
                .totalPaidValue(Money.zero(TEST_CURRENCY))
                .origin(CategoryOrigin.USER_CREATED)
                .build();

        CashCategory uncategorizedInflow = CashCategory.builder()
                .categoryName(new CategoryName("Uncategorized"))
                .category(new Category("Uncategorized"))
                .subCategories(new LinkedList<>())
                .groupedTransactions(new GroupedTransactions())
                .totalPaidValue(Money.zero(TEST_CURRENCY))
                .origin(CategoryOrigin.SYSTEM)
                .build();

        inflowCategories.add(salaryCategory);
        inflowCategories.add(uncategorizedInflow);

        // Outflow categories
        List<CashCategory> outflowCategories = new ArrayList<>();

        CashCategory utilitiesCategory = CashCategory.builder()
                .categoryName(new CategoryName("Utilities"))
                .category(new Category("Utilities"))
                .subCategories(new LinkedList<>())
                .groupedTransactions(new GroupedTransactions())
                .totalPaidValue(Money.zero(TEST_CURRENCY))
                .origin(CategoryOrigin.USER_CREATED)
                .build();

        CashCategory housingCategory = CashCategory.builder()
                .categoryName(new CategoryName("Housing"))
                .category(new Category("Housing"))
                .subCategories(new LinkedList<>(List.of(utilitiesCategory)))
                .groupedTransactions(new GroupedTransactions())
                .totalPaidValue(Money.zero(TEST_CURRENCY))
                .origin(CategoryOrigin.USER_CREATED)
                .build();

        CashCategory uncategorizedOutflow = CashCategory.builder()
                .categoryName(new CategoryName("Uncategorized"))
                .category(new Category("Uncategorized"))
                .subCategories(new LinkedList<>())
                .groupedTransactions(new GroupedTransactions())
                .totalPaidValue(Money.zero(TEST_CURRENCY))
                .origin(CategoryOrigin.SYSTEM)
                .build();

        outflowCategories.add(housingCategory);
        outflowCategories.add(uncategorizedOutflow);

        CashFlowMonthlyForecast activeForecast = CashFlowMonthlyForecast.builder()
                .period(YearMonth.of(2022, 1))
                .cashFlowStats(createCashFlowStats())
                .categorizedInFlows(inflowCategories)
                .categorizedOutFlows(outflowCategories)
                .status(CashFlowMonthlyForecast.Status.ACTIVE)
                .build();

        forecasts.put(YearMonth.of(2022, 1), activeForecast);

        return CashFlowForecastStatement.builder()
                .cashFlowId(cashFlowId)
                .forecasts(forecasts)
                .bankAccountNumber(BankAccountNumber.fromIban("PL61109010140000071219812874", Currency.of(TEST_CURRENCY)))
                .categoryStructure(createSimpleCategoryStructure())
                .lastModification(FIXED_NOW)
                .lastMessageChecksum(new Checksum("test-checksum-nested"))
                .build();
    }

    private CashFlowForecastStatement createForecastStatementWithTransactions(CashFlowId cashFlowId) {
        Map<YearMonth, CashFlowMonthlyForecast> forecasts = new LinkedHashMap<>();

        // Create transactions
        TransactionDetails paidTransaction = TransactionDetails.builder()
                .cashChangeId(TestIds.nextCashChangeId())
                .name(new Name("January Salary"))
                .money(Money.of(15000, TEST_CURRENCY))
                .created(FIXED_NOW)
                .dueDate(FIXED_NOW.plusDays(5))
                .build();

        TransactionDetails expectedTransaction = TransactionDetails.builder()
                .cashChangeId(TestIds.nextCashChangeId())
                .name(new Name("February Salary"))
                .money(Money.of(15000, TEST_CURRENCY))
                .created(FIXED_NOW)
                .dueDate(FIXED_NOW.plusMonths(1))
                .build();

        Map<PaymentStatus, List<TransactionDetails>> transactions = new HashMap<>();
        transactions.put(PaymentStatus.PAID, new LinkedList<>(List.of(paidTransaction)));
        transactions.put(PaymentStatus.EXPECTED, new LinkedList<>(List.of(expectedTransaction)));
        transactions.put(PaymentStatus.FORECAST, new LinkedList<>());

        CashCategory salaryCategory = CashCategory.builder()
                .categoryName(new CategoryName("Salary"))
                .category(new Category("Salary"))
                .subCategories(new LinkedList<>())
                .groupedTransactions(new GroupedTransactions(transactions))
                .totalPaidValue(Money.of(15000, TEST_CURRENCY))
                .origin(CategoryOrigin.USER_CREATED)
                .build();

        List<CashCategory> inflowCategories = new ArrayList<>(List.of(salaryCategory));
        List<CashCategory> outflowCategories = new ArrayList<>(List.of(createUncategorizedCategory()));

        CashFlowMonthlyForecast activeForecast = CashFlowMonthlyForecast.builder()
                .period(YearMonth.of(2022, 1))
                .cashFlowStats(createCashFlowStats())
                .categorizedInFlows(inflowCategories)
                .categorizedOutFlows(outflowCategories)
                .status(CashFlowMonthlyForecast.Status.ACTIVE)
                .build();

        forecasts.put(YearMonth.of(2022, 1), activeForecast);

        return CashFlowForecastStatement.builder()
                .cashFlowId(cashFlowId)
                .forecasts(forecasts)
                .bankAccountNumber(BankAccountNumber.fromIban("PL61109010140000071219812874", Currency.of(TEST_CURRENCY)))
                .categoryStructure(createSimpleCategoryStructure())
                .lastModification(FIXED_NOW)
                .lastMessageChecksum(new Checksum("test-checksum-transactions"))
                .build();
    }

    private CashFlowForecastStatement createForecastStatementWithCategoryStructure(CashFlowId cashFlowId) {
        Map<YearMonth, CashFlowMonthlyForecast> forecasts = new LinkedHashMap<>();
        forecasts.put(YearMonth.of(2022, 1), createMonthlyForecast(
                YearMonth.of(2022, 1), CashFlowMonthlyForecast.Status.ACTIVE));

        // Create nested category structure
        CategoryNode bonusNode = new CategoryNode(null, new CategoryName("Bonus"), new LinkedList<>());
        CategoryNode salaryNode = new CategoryNode(null, new CategoryName("Salary"), new LinkedList<>(List.of(bonusNode)));
        salaryNode.setOrigin(CategoryOrigin.USER_CREATED);

        CategoryNode uncategorizedInflowNode = new CategoryNode(null, new CategoryName("Uncategorized"), new LinkedList<>());
        uncategorizedInflowNode.setOrigin(CategoryOrigin.SYSTEM);

        CategoryNode utilitiesNode = new CategoryNode(null, new CategoryName("Utilities"), new LinkedList<>());
        CategoryNode housingNode = new CategoryNode(null, new CategoryName("Housing"), new LinkedList<>(List.of(utilitiesNode)));
        housingNode.setOrigin(CategoryOrigin.USER_CREATED);

        CategoryNode uncategorizedOutflowNode = new CategoryNode(null, new CategoryName("Uncategorized"), new LinkedList<>());
        uncategorizedOutflowNode.setOrigin(CategoryOrigin.SYSTEM);

        CurrentCategoryStructure categoryStructure = new CurrentCategoryStructure(
                List.of(salaryNode, uncategorizedInflowNode),
                List.of(housingNode, uncategorizedOutflowNode),
                FIXED_NOW
        );

        return CashFlowForecastStatement.builder()
                .cashFlowId(cashFlowId)
                .forecasts(forecasts)
                .bankAccountNumber(BankAccountNumber.fromIban("PL61109010140000071219812874", Currency.of(TEST_CURRENCY)))
                .categoryStructure(categoryStructure)
                .lastModification(FIXED_NOW)
                .lastMessageChecksum(new Checksum("test-checksum-structure"))
                .build();
    }

    private CashFlowForecastStatement createForecastStatementWithAttestation(CashFlowId cashFlowId) {
        Map<YearMonth, CashFlowMonthlyForecast> forecasts = new LinkedHashMap<>();

        Attestation attestation = new Attestation(
                Money.of(10000, TEST_CURRENCY),
                Attestation.Type.MANUAL,
                FIXED_NOW
        );

        CashFlowMonthlyForecast importedForecast = CashFlowMonthlyForecast.builder()
                .period(YearMonth.of(2021, 12))
                .cashFlowStats(createCashFlowStats())
                .categorizedInFlows(List.of(createUncategorizedCategory()))
                .categorizedOutFlows(List.of(createUncategorizedCategory()))
                .status(CashFlowMonthlyForecast.Status.IMPORTED)
                .attestation(attestation)
                .build();

        forecasts.put(YearMonth.of(2021, 12), importedForecast);
        forecasts.put(YearMonth.of(2022, 1), createMonthlyForecast(
                YearMonth.of(2022, 1), CashFlowMonthlyForecast.Status.ACTIVE));

        return CashFlowForecastStatement.builder()
                .cashFlowId(cashFlowId)
                .forecasts(forecasts)
                .bankAccountNumber(BankAccountNumber.fromIban("PL61109010140000071219812874", Currency.of(TEST_CURRENCY)))
                .categoryStructure(createSimpleCategoryStructure())
                .lastModification(FIXED_NOW)
                .lastMessageChecksum(new Checksum("test-checksum-attestation"))
                .build();
    }

    private CashFlowMonthlyForecast createMonthlyForecast(YearMonth period, CashFlowMonthlyForecast.Status status) {
        return CashFlowMonthlyForecast.builder()
                .period(period)
                .cashFlowStats(createCashFlowStats())
                .categorizedInFlows(List.of(createUncategorizedCategory()))
                .categorizedOutFlows(List.of(createUncategorizedCategory()))
                .status(status)
                .build();
    }

    private CashCategory createUncategorizedCategory() {
        return CashCategory.builder()
                .categoryName(new CategoryName("Uncategorized"))
                .category(new Category("Uncategorized"))
                .subCategories(new LinkedList<>())
                .groupedTransactions(new GroupedTransactions())
                .totalPaidValue(Money.zero(TEST_CURRENCY))
                .origin(CategoryOrigin.SYSTEM)
                .build();
    }

    private CashFlowStats createCashFlowStats() {
        return new CashFlowStats(
                Money.of(10000, TEST_CURRENCY),
                Money.of(12000, TEST_CURRENCY),
                Money.of(2000, TEST_CURRENCY),
                new CashSummary(
                        Money.of(5000, TEST_CURRENCY),
                        Money.of(2000, TEST_CURRENCY),
                        Money.zero(TEST_CURRENCY)
                ),
                new CashSummary(
                        Money.of(3000, TEST_CURRENCY),
                        Money.zero(TEST_CURRENCY),
                        Money.zero(TEST_CURRENCY)
                )
        );
    }

    private CurrentCategoryStructure createSimpleCategoryStructure() {
        CategoryNode uncategorizedInflow = new CategoryNode(null, new CategoryName("Uncategorized"), new LinkedList<>());
        CategoryNode uncategorizedOutflow = new CategoryNode(null, new CategoryName("Uncategorized"), new LinkedList<>());

        return new CurrentCategoryStructure(
                List.of(uncategorizedInflow),
                List.of(uncategorizedOutflow),
                FIXED_NOW
        );
    }
}
