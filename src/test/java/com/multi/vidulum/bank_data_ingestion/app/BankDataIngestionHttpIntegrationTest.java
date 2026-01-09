package com.multi.vidulum.bank_data_ingestion.app;

import com.multi.vidulum.bank_data_ingestion.domain.MappingAction;
import com.multi.vidulum.bank_data_ingestion.infrastructure.CategoryMappingMongoRepository;
import com.multi.vidulum.bank_data_ingestion.infrastructure.ImportJobMongoRepository;
import com.multi.vidulum.bank_data_ingestion.infrastructure.StagedTransactionMongoRepository;
import com.multi.vidulum.cashflow.app.CashFlowDto;
import com.multi.vidulum.cashflow.domain.CashChangeStatus;
import com.multi.vidulum.cashflow.domain.CashFlow;
import com.multi.vidulum.cashflow.domain.Category;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.CategoryOrigin;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.cashflow.infrastructure.CashFlowMongoRepository;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.cashflow_forecast_processor.infrastructure.CashFlowForecastMongoRepository;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.config.FixedClockConfig;
import com.multi.vidulum.portfolio.app.PortfolioAppConfig;
import com.multi.vidulum.trading.app.TradingAppConfig;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.utils.ContainerTestUtils;
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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying REST API communication between bank-data-ingestion
 * and cashflow-service endpoints.
 *
 * This test verifies the HTTP REST API contracts that HttpCashFlowServiceClient
 * would use in a microservice architecture:
 * 1. GET /cash-flow/{id} - get CashFlow info
 * 2. POST /cash-flow/{id}/category - create category
 * 3. POST /cash-flow/{id}/import-historical - import transaction
 * 4. POST /api/v1/bank-data-ingestion/{id}/stage - stage transactions
 * 5. POST /api/v1/bank-data-ingestion/{id}/import - start import
 *
 * Uses BankDataIngestionHttpActor for cleaner test code following the DualBudgetActor pattern.
 */
@Slf4j
@SpringBootTest(
        classes = FixedClockConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Import({PortfolioAppConfig.class, TradingAppConfig.class, BankDataIngestionHttpIntegrationTest.TestSecurityConfig.class, BankDataIngestionHttpIntegrationTest.TestCashFlowServiceClientConfig.class})
@Testcontainers
@DirtiesContext
public class BankDataIngestionHttpIntegrationTest {

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
        // Disable HttpCashFlowServiceClient - we'll provide a test implementation
        registry.add("vidulum.cashflow-service.enabled", () -> "false");
    }

    /**
     * Test configuration that provides CashFlowServiceClient using direct gateway calls.
     * Uses @Lazy on CommandGateway to break circular dependency.
     */
    @TestConfiguration
    static class TestCashFlowServiceClientConfig {
        @Bean
        public CashFlowServiceClient cashFlowServiceClient(
                com.multi.vidulum.shared.cqrs.QueryGateway queryGateway,
                @org.springframework.context.annotation.Lazy com.multi.vidulum.shared.cqrs.CommandGateway commandGateway) {
            return new TestCashFlowServiceClient(queryGateway, commandGateway);
        }
    }

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private CashFlowMongoRepository cashFlowMongoRepository;

    @Autowired
    private CashFlowForecastMongoRepository cashFlowForecastMongoRepository;

    @Autowired
    private CategoryMappingMongoRepository categoryMappingMongoRepository;

    @Autowired
    private StagedTransactionMongoRepository stagedTransactionMongoRepository;

    @Autowired
    private ImportJobMongoRepository importJobMongoRepository;

    private BankDataIngestionHttpActor actor;

    @BeforeEach
    public void beforeTest() {
        kafkaListenerEndpointRegistry.getListenerContainers().forEach(
                messageListenerContainer -> ContainerTestUtils.waitForAssignment(messageListenerContainer, 1));
        categoryMappingMongoRepository.deleteAll();
        stagedTransactionMongoRepository.deleteAll();
        importJobMongoRepository.deleteAll();
        cashFlowMongoRepository.deleteAll();
        cashFlowForecastMongoRepository.deleteAll();

        actor = new BankDataIngestionHttpActor(restTemplate, port);
    }

    @Test
    @DisplayName("Should get CashFlow info via REST API - verifies GET /cash-flow/{id}")
    void shouldGetCashFlowInfoViaRestApi() {
        // given
        YearMonth startPeriod = YearMonth.of(2021, 7);
        YearMonth activePeriod = YearMonth.of(2022, 1); // FixedClockConfig sets clock to 2022-01-01

        String cashFlowId = actor.createCashFlowWithHistory(
                "test-user-123",
                "Test CashFlow",
                startPeriod,
                Money.of(10000.0, "PLN")
        );

        // when
        CashFlowDto.CashFlowSummaryJson cashFlow = actor.getCashFlow(cashFlowId);

        // then - validate whole object using recursive comparison
        assertThat(cashFlow)
                .usingRecursiveComparison()
                .ignoringFields("created", "lastModification", "importCutoffDateTime", "lastMessageChecksum",
                        "inflowCategories", "outflowCategories", "bankAccount")
                .isEqualTo(CashFlowDto.CashFlowSummaryJson.builder()
                        .cashFlowId(cashFlowId)
                        .userId("test-user-123")
                        .name("Test CashFlow")
                        .description("CashFlow for HTTP integration testing")
                        .status(CashFlow.CashFlowStatus.SETUP)
                        .startPeriod(startPeriod)
                        .activePeriod(activePeriod)
                        .cashChanges(Map.of())
                        .build());

        // Validate system-created "Uncategorized" categories
        assertThat(cashFlow.getInflowCategories()).hasSize(1);
        assertThat(cashFlow.getInflowCategories().get(0).getCategoryName().name()).isEqualTo("Uncategorized");
        assertThat(cashFlow.getOutflowCategories()).hasSize(1);
        assertThat(cashFlow.getOutflowCategories().get(0).getCategoryName().name()).isEqualTo("Uncategorized");

        // Validate bank account
        assertThat(cashFlow.getBankAccount().bankName().name()).isEqualTo("Test Bank");
        assertThat(cashFlow.getBankAccount().bankAccountNumber().account()).isEqualTo("PL12345678901234567890123456");
        assertThat(cashFlow.getBankAccount().bankAccountNumber().denomination()).isEqualTo(Currency.of("PLN"));

        log.info("CashFlow validated: id={}, status={}, startPeriod={}, activePeriod={}",
                cashFlow.getCashFlowId(), cashFlow.getStatus(), cashFlow.getStartPeriod(), cashFlow.getActivePeriod());
    }

    @Test
    @DisplayName("Should create category via REST API - verifies POST /cash-flow/{id}/category")
    void shouldCreateCategoryViaRestApi() {
        // given
        String cashFlowId = actor.createCashFlowWithHistory(
                "test-user-123",
                "Test CashFlow",
                YearMonth.of(2021, 7),
                Money.of(10000.0, "PLN")
        );

        // when
        actor.createCategory(cashFlowId, "NewCategory", Type.OUTFLOW);

        // then
        CashFlowDto.CashFlowSummaryJson cashFlow = actor.getCashFlow(cashFlowId);

        // Validate structure
        assertThat(cashFlow.getOutflowCategories()).hasSize(2); // Uncategorized + NewCategory

        // Find and validate the created category
        Category createdCategory = cashFlow.getOutflowCategories().stream()
                .filter(c -> "NewCategory".equals(c.getCategoryName().name()))
                .findFirst()
                .orElseThrow();

        assertThat(createdCategory)
                .usingRecursiveComparison()
                .ignoringFields("validFrom", "validTo")
                .isEqualTo(Category.builder()
                        .categoryName(new CategoryName("NewCategory"))
                        .isModifiable(true)
                        .archived(false)
                        .subCategories(List.of())
                        .origin(CategoryOrigin.USER_CREATED)
                        .build());

        log.info("Category created: name={}, archived={}", createdCategory.getCategoryName().name(), createdCategory.isArchived());
    }

    @Test
    @DisplayName("Should create subcategory via REST API")
    void shouldCreateSubcategoryViaRestApi() {
        // given
        String cashFlowId = actor.createCashFlowWithHistory(
                "test-user-123",
                "Test CashFlow",
                YearMonth.of(2021, 7),
                Money.of(10000.0, "PLN")
        );

        // when
        actor.createCategory(cashFlowId, "ParentCategory", Type.OUTFLOW);
        actor.createCategory(cashFlowId, "ChildCategory", "ParentCategory", Type.OUTFLOW);

        // then
        CashFlowDto.CashFlowSummaryJson cashFlow = actor.getCashFlow(cashFlowId);
        assertThat(cashFlow.getOutflowCategories()).hasSize(2); // Uncategorized + ParentCategory

        // Find parent and validate hierarchy
        Category parentCategory = cashFlow.getOutflowCategories().stream()
                .filter(c -> "ParentCategory".equals(c.getCategoryName().name()))
                .findFirst()
                .orElseThrow();

        assertThat(parentCategory.getSubCategories()).hasSize(1);
        Category childCategory = parentCategory.getSubCategories().get(0);

        assertThat(childCategory)
                .usingRecursiveComparison()
                .ignoringFields("validFrom", "validTo")
                .isEqualTo(Category.builder()
                        .categoryName(new CategoryName("ChildCategory"))
                        .isModifiable(true)
                        .archived(false)
                        .subCategories(List.of())
                        .origin(CategoryOrigin.USER_CREATED)
                        .build());

        log.info("Subcategory hierarchy: {} -> {}", parentCategory.getCategoryName().name(), childCategory.getCategoryName().name());
    }

    @Test
    @DisplayName("Should import multiple historical transactions across categories and months via REST API")
    void shouldImportHistoricalTransactionViaRestApi() {
        // given - create CashFlow with 6 months history (2021-07 to 2021-12)
        YearMonth startPeriod = YearMonth.of(2021, 7);

        String cashFlowId = actor.createCashFlowWithHistory(
                "test-user-123",
                "Test CashFlow",
                startPeriod,
                Money.of(10000.0, "PLN")
        );

        // Create category hierarchy:
        // OUTFLOW:
        //   - Housing
        //     - Rent
        //     - Utilities
        //   - Food
        //     - Groceries
        //     - Restaurants
        //   - Transportation
        // INFLOW:
        //   - Income
        //     - Salary
        //     - Bonus

        // Outflow categories
        actor.createCategory(cashFlowId, "Housing", Type.OUTFLOW);
        actor.createCategory(cashFlowId, "Rent", "Housing", Type.OUTFLOW);
        actor.createCategory(cashFlowId, "Utilities", "Housing", Type.OUTFLOW);
        actor.createCategory(cashFlowId, "Food", Type.OUTFLOW);
        actor.createCategory(cashFlowId, "Groceries", "Food", Type.OUTFLOW);
        actor.createCategory(cashFlowId, "Restaurants", "Food", Type.OUTFLOW);
        actor.createCategory(cashFlowId, "Transportation", Type.OUTFLOW);

        // Inflow categories
        actor.createCategory(cashFlowId, "Income", Type.INFLOW);
        actor.createCategory(cashFlowId, "Salary", "Income", Type.INFLOW);
        actor.createCategory(cashFlowId, "Bonus", "Income", Type.INFLOW);

        // when - import transactions across different months and categories

        // === July 2021 (startPeriod) ===
        ZonedDateTime july15 = ZonedDateTime.of(2021, 7, 15, 10, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime july25 = ZonedDateTime.of(2021, 7, 25, 14, 0, 0, 0, ZoneOffset.UTC);

        String salaryJuly = actor.importHistoricalTransaction(cashFlowId, "Salary", "July Salary",
                "Monthly salary payment", Money.of(5000.0, "PLN"), Type.INFLOW, july15, july15);
        String rentJuly = actor.importHistoricalTransaction(cashFlowId, "Rent", "July Rent",
                "Monthly rent payment", Money.of(1500.0, "PLN"), Type.OUTFLOW, july15, july15);
        String groceriesJuly = actor.importHistoricalTransaction(cashFlowId, "Groceries", "Weekly groceries",
                "Biedronka shopping", Money.of(250.0, "PLN"), Type.OUTFLOW, july25, july25);

        // === August 2021 ===
        ZonedDateTime aug10 = ZonedDateTime.of(2021, 8, 10, 9, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime aug15 = ZonedDateTime.of(2021, 8, 15, 12, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime aug20 = ZonedDateTime.of(2021, 8, 20, 18, 0, 0, 0, ZoneOffset.UTC);

        String salaryAug = actor.importHistoricalTransaction(cashFlowId, "Salary", "August Salary",
                "Monthly salary payment", Money.of(5000.0, "PLN"), Type.INFLOW, aug10, aug10);
        String rentAug = actor.importHistoricalTransaction(cashFlowId, "Rent", "August Rent",
                "Monthly rent payment", Money.of(1500.0, "PLN"), Type.OUTFLOW, aug15, aug15);
        String utilitiesAug = actor.importHistoricalTransaction(cashFlowId, "Utilities", "Electricity bill",
                "PGE electricity", Money.of(180.0, "PLN"), Type.OUTFLOW, aug20, aug20);
        String restaurantAug = actor.importHistoricalTransaction(cashFlowId, "Restaurants", "Dinner out",
                "Restaurant visit", Money.of(150.0, "PLN"), Type.OUTFLOW, aug20, aug20);

        // === September 2021 ===
        ZonedDateTime sep5 = ZonedDateTime.of(2021, 9, 5, 11, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime sep15 = ZonedDateTime.of(2021, 9, 15, 10, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime sep28 = ZonedDateTime.of(2021, 9, 28, 16, 0, 0, 0, ZoneOffset.UTC);

        String salarySep = actor.importHistoricalTransaction(cashFlowId, "Salary", "September Salary",
                "Monthly salary payment", Money.of(5000.0, "PLN"), Type.INFLOW, sep5, sep5);
        String bonusSep = actor.importHistoricalTransaction(cashFlowId, "Bonus", "Q3 Bonus",
                "Quarterly performance bonus", Money.of(2000.0, "PLN"), Type.INFLOW, sep28, sep28);
        String rentSep = actor.importHistoricalTransaction(cashFlowId, "Rent", "September Rent",
                "Monthly rent payment", Money.of(1500.0, "PLN"), Type.OUTFLOW, sep15, sep15);
        String transportSep = actor.importHistoricalTransaction(cashFlowId, "Transportation", "Fuel",
                "Orlen gas station", Money.of(200.0, "PLN"), Type.OUTFLOW, sep15, sep15);

        // === October 2021 ===
        ZonedDateTime oct10 = ZonedDateTime.of(2021, 10, 10, 10, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime oct25 = ZonedDateTime.of(2021, 10, 25, 14, 0, 0, 0, ZoneOffset.UTC);

        String salaryOct = actor.importHistoricalTransaction(cashFlowId, "Salary", "October Salary",
                "Monthly salary payment", Money.of(5200.0, "PLN"), Type.INFLOW, oct10, oct10);
        String rentOct = actor.importHistoricalTransaction(cashFlowId, "Rent", "October Rent",
                "Monthly rent payment", Money.of(1500.0, "PLN"), Type.OUTFLOW, oct10, oct10);
        String groceriesOct1 = actor.importHistoricalTransaction(cashFlowId, "Groceries", "Weekly groceries #1",
                "Lidl shopping", Money.of(180.0, "PLN"), Type.OUTFLOW, oct10, oct10);
        String groceriesOct2 = actor.importHistoricalTransaction(cashFlowId, "Groceries", "Weekly groceries #2",
                "Carrefour shopping", Money.of(220.0, "PLN"), Type.OUTFLOW, oct25, oct25);

        // === November 2021 ===
        ZonedDateTime nov5 = ZonedDateTime.of(2021, 11, 5, 9, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime nov15 = ZonedDateTime.of(2021, 11, 15, 12, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime nov20 = ZonedDateTime.of(2021, 11, 20, 15, 0, 0, 0, ZoneOffset.UTC);

        String salaryNov = actor.importHistoricalTransaction(cashFlowId, "Salary", "November Salary",
                "Monthly salary payment", Money.of(5200.0, "PLN"), Type.INFLOW, nov5, nov5);
        String rentNov = actor.importHistoricalTransaction(cashFlowId, "Rent", "November Rent",
                "Monthly rent payment", Money.of(1500.0, "PLN"), Type.OUTFLOW, nov15, nov15);
        String utilitiesNov = actor.importHistoricalTransaction(cashFlowId, "Utilities", "Gas bill",
                "PGNiG gas", Money.of(120.0, "PLN"), Type.OUTFLOW, nov20, nov20);

        // === December 2021 (last historical month before activePeriod 2022-01) ===
        ZonedDateTime dec10 = ZonedDateTime.of(2021, 12, 10, 10, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime dec15 = ZonedDateTime.of(2021, 12, 15, 12, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime dec24 = ZonedDateTime.of(2021, 12, 24, 11, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime dec28 = ZonedDateTime.of(2021, 12, 28, 14, 0, 0, 0, ZoneOffset.UTC);

        String salaryDec = actor.importHistoricalTransaction(cashFlowId, "Salary", "December Salary",
                "Monthly salary payment", Money.of(5200.0, "PLN"), Type.INFLOW, dec10, dec10);
        String bonusDec = actor.importHistoricalTransaction(cashFlowId, "Bonus", "Christmas Bonus",
                "End of year bonus", Money.of(3000.0, "PLN"), Type.INFLOW, dec24, dec24);
        String rentDec = actor.importHistoricalTransaction(cashFlowId, "Rent", "December Rent",
                "Monthly rent payment", Money.of(1500.0, "PLN"), Type.OUTFLOW, dec15, dec15);
        String groceriesDec = actor.importHistoricalTransaction(cashFlowId, "Groceries", "Christmas shopping",
                "Holiday groceries", Money.of(500.0, "PLN"), Type.OUTFLOW, dec24, dec24);
        String restaurantDec = actor.importHistoricalTransaction(cashFlowId, "Restaurants", "Christmas dinner",
                "Family dinner restaurant", Money.of(400.0, "PLN"), Type.OUTFLOW, dec28, dec28);

        // then - validate the full CashFlow state
        CashFlowDto.CashFlowSummaryJson cashFlow = actor.getCashFlow(cashFlowId);

        // Validate CashFlow basic info
        assertThat(cashFlow.getCashFlowId()).isEqualTo(cashFlowId);
        assertThat(cashFlow.getStatus()).isEqualTo(CashFlow.CashFlowStatus.SETUP);
        assertThat(cashFlow.getStartPeriod()).isEqualTo(startPeriod);

        // Validate total number of transactions (23 transactions imported)
        // July: 3, August: 4, September: 4, October: 4, November: 3, December: 5 = 23
        assertThat(cashFlow.getCashChanges()).hasSize(23);

        // Validate all transaction IDs are present
        assertThat(cashFlow.getCashChanges().keySet()).containsExactlyInAnyOrder(
                salaryJuly, rentJuly, groceriesJuly,
                salaryAug, rentAug, utilitiesAug, restaurantAug,
                salarySep, bonusSep, rentSep, transportSep,
                salaryOct, rentOct, groceriesOct1, groceriesOct2,
                salaryNov, rentNov, utilitiesNov,
                salaryDec, bonusDec, rentDec, groceriesDec, restaurantDec
        );

        // Validate sample transactions using recursive comparison
        CashFlowDto.CashChangeSummaryJson julySalaryTx = cashFlow.getCashChanges().get(salaryJuly);
        assertThat(julySalaryTx)
                .usingRecursiveComparison()
                .ignoringFields("created", "dueDate", "endDate")
                .isEqualTo(CashFlowDto.CashChangeSummaryJson.builder()
                        .cashChangeId(salaryJuly)
                        .name("July Salary")
                        .description("Monthly salary payment")
                        .categoryName("Salary")
                        .type(Type.INFLOW)
                        .money(Money.of(5000.0, "PLN"))
                        .status(CashChangeStatus.CONFIRMED)
                        .build());

        CashFlowDto.CashChangeSummaryJson q3BonusTx = cashFlow.getCashChanges().get(bonusSep);
        assertThat(q3BonusTx)
                .usingRecursiveComparison()
                .ignoringFields("created", "dueDate", "endDate")
                .isEqualTo(CashFlowDto.CashChangeSummaryJson.builder()
                        .cashChangeId(bonusSep)
                        .name("Q3 Bonus")
                        .description("Quarterly performance bonus")
                        .categoryName("Bonus")
                        .type(Type.INFLOW)
                        .money(Money.of(2000.0, "PLN"))
                        .status(CashChangeStatus.CONFIRMED)
                        .build());

        CashFlowDto.CashChangeSummaryJson christmasGroceriesTx = cashFlow.getCashChanges().get(groceriesDec);
        assertThat(christmasGroceriesTx)
                .usingRecursiveComparison()
                .ignoringFields("created", "dueDate", "endDate")
                .isEqualTo(CashFlowDto.CashChangeSummaryJson.builder()
                        .cashChangeId(groceriesDec)
                        .name("Christmas shopping")
                        .description("Holiday groceries")
                        .categoryName("Groceries")
                        .type(Type.OUTFLOW)
                        .money(Money.of(500.0, "PLN"))
                        .status(CashChangeStatus.CONFIRMED)
                        .build());

        // Validate category structure
        assertThat(cashFlow.getOutflowCategories()).hasSize(4); // Uncategorized + Housing + Food + Transportation
        assertThat(cashFlow.getInflowCategories()).hasSize(2);  // Uncategorized + Income

        // Find and validate Housing category with subcategories
        Category housingCategory = cashFlow.getOutflowCategories().stream()
                .filter(c -> "Housing".equals(c.getCategoryName().name()))
                .findFirst()
                .orElseThrow();
        assertThat(housingCategory.getSubCategories()).hasSize(2); // Rent, Utilities
        assertThat(housingCategory.getSubCategories().stream().map(c -> c.getCategoryName().name()))
                .containsExactlyInAnyOrder("Rent", "Utilities");

        // Find and validate Income category with subcategories
        Category incomeCategory = cashFlow.getInflowCategories().stream()
                .filter(c -> "Income".equals(c.getCategoryName().name()))
                .findFirst()
                .orElseThrow();
        assertThat(incomeCategory.getSubCategories()).hasSize(2); // Salary, Bonus
        assertThat(incomeCategory.getSubCategories().stream().map(c -> c.getCategoryName().name()))
                .containsExactlyInAnyOrder("Salary", "Bonus");

        // Calculate and log totals by type
        double totalInflow = cashFlow.getCashChanges().values().stream()
                .filter(tx -> tx.getType() == Type.INFLOW)
                .mapToDouble(tx -> tx.getMoney().getAmount().doubleValue())
                .sum();
        double totalOutflow = cashFlow.getCashChanges().values().stream()
                .filter(tx -> tx.getType() == Type.OUTFLOW)
                .mapToDouble(tx -> tx.getMoney().getAmount().doubleValue())
                .sum();

        log.info("Historical import completed:");
        log.info("  - Total transactions: {}", cashFlow.getCashChanges().size());
        log.info("  - Total inflow: {} PLN", totalInflow);
        log.info("  - Total outflow: {} PLN", totalOutflow);
        log.info("  - Net balance change: {} PLN", totalInflow - totalOutflow);
        log.info("  - Months covered: {} to {}", startPeriod, YearMonth.of(2021, 12));
    }

    @Test
    @DisplayName("Should configure and retrieve category mappings via REST API")
    void shouldConfigureAndRetrieveMappingsViaRestApi() {
        // given
        String cashFlowId = actor.createCashFlowWithHistory(
                "test-user-123",
                "Test CashFlow",
                YearMonth.of(2021, 7),
                Money.of(10000.0, "PLN")
        );

        // Create a category that will be used as target
        actor.createCategory(cashFlowId, "Groceries", Type.OUTFLOW);

        // when: configure mappings
        actor.configureMappings(cashFlowId, List.of(
                actor.mappingToExisting("Zakupy kartą", "Groceries", Type.OUTFLOW),
                actor.mappingCreateNew("Streaming", "Entertainment", Type.OUTFLOW)
        ));

        // then: validate mappings response
        BankDataIngestionDto.GetMappingsResponse mappingsResponse = actor.getMappings(cashFlowId);
        assertThat(mappingsResponse.getMappings()).hasSize(2);

        // Validate first mapping (MAP_TO_EXISTING) using recursive comparison
        BankDataIngestionDto.MappingJson groceriesMapping = mappingsResponse.getMappings().stream()
                .filter(m -> "Zakupy kartą".equals(m.getBankCategoryName()))
                .findFirst()
                .orElseThrow();

        assertThat(groceriesMapping)
                .usingRecursiveComparison()
                .ignoringFields("mappingId", "createdAt", "updatedAt", "parentCategoryName")
                .isEqualTo(BankDataIngestionDto.MappingJson.builder()
                        .bankCategoryName("Zakupy kartą")
                        .action(MappingAction.MAP_TO_EXISTING)
                        .targetCategoryName("Groceries")
                        .categoryType(Type.OUTFLOW)
                        .build());
        assertThat(groceriesMapping.getMappingId()).isNotNull();
        assertThat(groceriesMapping.getCreatedAt()).isNotNull();

        // Validate second mapping (CREATE_NEW) using recursive comparison
        BankDataIngestionDto.MappingJson streamingMapping = mappingsResponse.getMappings().stream()
                .filter(m -> "Streaming".equals(m.getBankCategoryName()))
                .findFirst()
                .orElseThrow();

        assertThat(streamingMapping)
                .usingRecursiveComparison()
                .ignoringFields("mappingId", "createdAt", "updatedAt", "parentCategoryName")
                .isEqualTo(BankDataIngestionDto.MappingJson.builder()
                        .bankCategoryName("Streaming")
                        .action(MappingAction.CREATE_NEW)
                        .targetCategoryName("Entertainment")
                        .categoryType(Type.OUTFLOW)
                        .build());
        assertThat(streamingMapping.getMappingId()).isNotNull();
        assertThat(streamingMapping.getCreatedAt()).isNotNull();

        log.info("Mappings validated: {} -> {} ({}), {} -> {} ({})",
                groceriesMapping.getBankCategoryName(), groceriesMapping.getTargetCategoryName(), groceriesMapping.getAction(),
                streamingMapping.getBankCategoryName(), streamingMapping.getTargetCategoryName(), streamingMapping.getAction());
    }

    // Note: Tests for stage/import endpoints are in BankDataIngestionControllerTest
    // which uses direct controller injection. The REST endpoints for staging and import
    // will be added in a future implementation.
}
