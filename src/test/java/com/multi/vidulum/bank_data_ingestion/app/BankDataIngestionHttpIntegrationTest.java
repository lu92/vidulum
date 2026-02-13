package com.multi.vidulum.bank_data_ingestion.app;

import com.multi.vidulum.bank_data_ingestion.domain.MappingAction;
import com.multi.vidulum.bank_data_ingestion.infrastructure.CategoryMappingMongoRepository;
import com.multi.vidulum.bank_data_ingestion.infrastructure.ImportJobMongoRepository;
import com.multi.vidulum.bank_data_ingestion.infrastructure.StagedTransactionMongoRepository;
import com.multi.vidulum.cashflow.app.CashFlowDto;
import com.multi.vidulum.cashflow.domain.BankAccount;
import com.multi.vidulum.cashflow.domain.BankAccountNumber;
import com.multi.vidulum.cashflow.domain.BankName;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying REST API communication between bank-data-ingestion
 * and cashflow-service endpoints.
 *
 * This test verifies the HTTP REST API contracts that HttpCashFlowServiceClient
 * would use in a microservice architecture:
 * 1. GET /cash-flow/cf={id} - get CashFlow info
 * 2. POST /cash-flow/cf={id}/category - create category
 * 3. POST /cash-flow/cf={id}/import-historical - import transaction
 * 4. POST /api/v1/bank-data-ingestion/cf={id}/stage - stage transactions
 * 5. POST /api/v1/bank-data-ingestion/cf={id}/import - start import
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

    // FixedClockConfig sets clock to 2022-01-01T00:00:00Z
    private static final ZonedDateTime FIXED_NOW = ZonedDateTime.parse("2022-01-01T00:00:00Z[UTC]");

    private static final AtomicInteger NAME_COUNTER = new AtomicInteger(0);

    private String uniqueCashFlowName() {
        return "Ingestion-" + NAME_COUNTER.incrementAndGet();
    }

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
    @DisplayName("Should get CashFlow info via REST API - verifies GET /cash-flow/cf={id}")
    void shouldGetCashFlowInfoViaRestApi() {
        // given
        YearMonth startPeriod = YearMonth.of(2021, 7);
        YearMonth activePeriod = YearMonth.of(2022, 1);
        String cashFlowName = uniqueCashFlowName();

        String cashFlowId = actor.createCashFlowWithHistory(
                "U10000006",
                cashFlowName,
                startPeriod,
                Money.of(10000.0, "PLN")
        );

        // when
        CashFlowDto.CashFlowSummaryJson cashFlow = actor.getCashFlow(cashFlowId);

        // then - validate whole object with all fields
        // Only ignore cashFlowId (generated), lastMessageChecksum (internal), importCutoffDateTime (not set)
        assertThat(cashFlow.getCashFlowId()).isEqualTo(cashFlowId);
        assertThat(cashFlow.getUserId()).isEqualTo("U10000006");
        assertThat(cashFlow.getName()).isEqualTo(cashFlowName);
        assertThat(cashFlow.getDescription()).isEqualTo("CashFlow for HTTP integration testing");
        assertThat(cashFlow.getStatus()).isEqualTo(CashFlow.CashFlowStatus.SETUP);
        assertThat(cashFlow.getStartPeriod()).isEqualTo(startPeriod);
        assertThat(cashFlow.getActivePeriod()).isEqualTo(activePeriod);
        assertThat(cashFlow.getCashChanges()).isEmpty();
        assertThat(cashFlow.getCreated()).isEqualTo(FIXED_NOW);

        // Validate bank account using recursive comparison
        assertThat(cashFlow.getBankAccount())
                .usingRecursiveComparison()
                .isEqualTo(BankAccount.fromIban(
                        "Test Bank",
                        "PL61109010140000071219812874",
                        Currency.of("PLN"),
                        Money.zero("PLN"),
                        null
                ));

        // Validate system-created "Uncategorized" categories
        assertThat(cashFlow.getInflowCategories()).hasSize(1);
        assertThat(cashFlow.getInflowCategories().get(0))
                .usingRecursiveComparison()
                .ignoringFields("validFrom", "validTo")
                .isEqualTo(Category.builder()
                        .categoryName(new CategoryName("Uncategorized"))
                        .isModifiable(false)
                        .archived(false)
                        .subCategories(List.of())
                        .origin(CategoryOrigin.SYSTEM)
                        .build());

        assertThat(cashFlow.getOutflowCategories()).hasSize(1);
        assertThat(cashFlow.getOutflowCategories().get(0))
                .usingRecursiveComparison()
                .ignoringFields("validFrom", "validTo")
                .isEqualTo(Category.builder()
                        .categoryName(new CategoryName("Uncategorized"))
                        .isModifiable(false)
                        .archived(false)
                        .subCategories(List.of())
                        .origin(CategoryOrigin.SYSTEM)
                        .build());

        log.info("CashFlow validated: id={}, status={}, startPeriod={}, activePeriod={}, created={}",
                cashFlow.getCashFlowId(), cashFlow.getStatus(), cashFlow.getStartPeriod(),
                cashFlow.getActivePeriod(), cashFlow.getCreated());
    }

    @Test
    @DisplayName("Should create category via REST API - verifies POST /cash-flow/cf={id}/category")
    void shouldCreateCategoryViaRestApi() {
        // given
        String cashFlowId = actor.createCashFlowWithHistory(
                "U10000006",
                uniqueCashFlowName(),
                YearMonth.of(2021, 7),
                Money.of(10000.0, "PLN")
        );

        // when
        actor.createCategory(cashFlowId, "NewCategory", Type.OUTFLOW);

        // then
        CashFlowDto.CashFlowSummaryJson cashFlow = actor.getCashFlow(cashFlowId);

        // Validate structure: Uncategorized (system) + NewCategory (user)
        assertThat(cashFlow.getOutflowCategories()).hasSize(2);

        // Find and validate the created category with full object comparison
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

        log.info("Category created: name={}, origin={}, archived={}",
                createdCategory.getCategoryName().name(), createdCategory.getOrigin(), createdCategory.isArchived());
    }

    @Test
    @DisplayName("Should create subcategory via REST API")
    void shouldCreateSubcategoryViaRestApi() {
        // given
        String cashFlowId = actor.createCashFlowWithHistory(
                "U10000006",
                uniqueCashFlowName(),
                YearMonth.of(2021, 7),
                Money.of(10000.0, "PLN")
        );

        // when
        actor.createCategory(cashFlowId, "ParentCategory", Type.OUTFLOW);
        actor.createCategory(cashFlowId, "ChildCategory", "ParentCategory", Type.OUTFLOW);

        // then
        CashFlowDto.CashFlowSummaryJson cashFlow = actor.getCashFlow(cashFlowId);
        assertThat(cashFlow.getOutflowCategories()).hasSize(2); // Uncategorized + ParentCategory

        // Find and validate parent with child
        Category parentCategory = cashFlow.getOutflowCategories().stream()
                .filter(c -> "ParentCategory".equals(c.getCategoryName().name()))
                .findFirst()
                .orElseThrow();

        assertThat(parentCategory.getSubCategories()).hasSize(1);

        // Validate child category with full object comparison
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
                "U10000006",
                uniqueCashFlowName(),
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
        ZonedDateTime july15 = ZonedDateTime.parse("2021-07-15T10:00:00Z[UTC]");
        ZonedDateTime july25 = ZonedDateTime.parse("2021-07-25T14:00:00Z[UTC]");

        String salaryJuly = actor.importHistoricalTransaction(cashFlowId, "Salary", "July Salary",
                "Monthly salary payment", Money.of(5000.0, "PLN"), Type.INFLOW, july15, july15);
        String rentJuly = actor.importHistoricalTransaction(cashFlowId, "Rent", "July Rent",
                "Monthly rent payment", Money.of(1500.0, "PLN"), Type.OUTFLOW, july15, july15);
        String groceriesJuly = actor.importHistoricalTransaction(cashFlowId, "Groceries", "Weekly groceries",
                "Biedronka shopping", Money.of(250.0, "PLN"), Type.OUTFLOW, july25, july25);

        // === August 2021 ===
        ZonedDateTime aug10 = ZonedDateTime.parse("2021-08-10T09:00:00Z[UTC]");
        ZonedDateTime aug15 = ZonedDateTime.parse("2021-08-15T12:00:00Z[UTC]");
        ZonedDateTime aug20 = ZonedDateTime.parse("2021-08-20T18:00:00Z[UTC]");

        String salaryAug = actor.importHistoricalTransaction(cashFlowId, "Salary", "August Salary",
                "Monthly salary payment", Money.of(5000.0, "PLN"), Type.INFLOW, aug10, aug10);
        String rentAug = actor.importHistoricalTransaction(cashFlowId, "Rent", "August Rent",
                "Monthly rent payment", Money.of(1500.0, "PLN"), Type.OUTFLOW, aug15, aug15);
        String utilitiesAug = actor.importHistoricalTransaction(cashFlowId, "Utilities", "Electricity bill",
                "PGE electricity", Money.of(180.0, "PLN"), Type.OUTFLOW, aug20, aug20);
        String restaurantAug = actor.importHistoricalTransaction(cashFlowId, "Restaurants", "Dinner out",
                "Restaurant visit", Money.of(150.0, "PLN"), Type.OUTFLOW, aug20, aug20);

        // === September 2021 ===
        ZonedDateTime sep5 = ZonedDateTime.parse("2021-09-05T11:00:00Z[UTC]");
        ZonedDateTime sep15 = ZonedDateTime.parse("2021-09-15T10:00:00Z[UTC]");
        ZonedDateTime sep28 = ZonedDateTime.parse("2021-09-28T16:00:00Z[UTC]");

        String salarySep = actor.importHistoricalTransaction(cashFlowId, "Salary", "September Salary",
                "Monthly salary payment", Money.of(5000.0, "PLN"), Type.INFLOW, sep5, sep5);
        String bonusSep = actor.importHistoricalTransaction(cashFlowId, "Bonus", "Q3 Bonus",
                "Quarterly performance bonus", Money.of(2000.0, "PLN"), Type.INFLOW, sep28, sep28);
        String rentSep = actor.importHistoricalTransaction(cashFlowId, "Rent", "September Rent",
                "Monthly rent payment", Money.of(1500.0, "PLN"), Type.OUTFLOW, sep15, sep15);
        String transportSep = actor.importHistoricalTransaction(cashFlowId, "Transportation", "Fuel",
                "Orlen gas station", Money.of(200.0, "PLN"), Type.OUTFLOW, sep15, sep15);

        // === October 2021 ===
        ZonedDateTime oct10 = ZonedDateTime.parse("2021-10-10T10:00:00Z[UTC]");
        ZonedDateTime oct25 = ZonedDateTime.parse("2021-10-25T14:00:00Z[UTC]");

        String salaryOct = actor.importHistoricalTransaction(cashFlowId, "Salary", "October Salary",
                "Monthly salary payment", Money.of(5200.0, "PLN"), Type.INFLOW, oct10, oct10);
        String rentOct = actor.importHistoricalTransaction(cashFlowId, "Rent", "October Rent",
                "Monthly rent payment", Money.of(1500.0, "PLN"), Type.OUTFLOW, oct10, oct10);
        String groceriesOct1 = actor.importHistoricalTransaction(cashFlowId, "Groceries", "Weekly groceries #1",
                "Lidl shopping", Money.of(180.0, "PLN"), Type.OUTFLOW, oct10, oct10);
        String groceriesOct2 = actor.importHistoricalTransaction(cashFlowId, "Groceries", "Weekly groceries #2",
                "Carrefour shopping", Money.of(220.0, "PLN"), Type.OUTFLOW, oct25, oct25);

        // === November 2021 ===
        ZonedDateTime nov5 = ZonedDateTime.parse("2021-11-05T09:00:00Z[UTC]");
        ZonedDateTime nov15 = ZonedDateTime.parse("2021-11-15T12:00:00Z[UTC]");
        ZonedDateTime nov20 = ZonedDateTime.parse("2021-11-20T15:00:00Z[UTC]");

        String salaryNov = actor.importHistoricalTransaction(cashFlowId, "Salary", "November Salary",
                "Monthly salary payment", Money.of(5200.0, "PLN"), Type.INFLOW, nov5, nov5);
        String rentNov = actor.importHistoricalTransaction(cashFlowId, "Rent", "November Rent",
                "Monthly rent payment", Money.of(1500.0, "PLN"), Type.OUTFLOW, nov15, nov15);
        String utilitiesNov = actor.importHistoricalTransaction(cashFlowId, "Utilities", "Gas bill",
                "PGNiG gas", Money.of(120.0, "PLN"), Type.OUTFLOW, nov20, nov20);

        // === December 2021 (last historical month before activePeriod 2022-01) ===
        ZonedDateTime dec10 = ZonedDateTime.parse("2021-12-10T10:00:00Z[UTC]");
        ZonedDateTime dec15 = ZonedDateTime.parse("2021-12-15T12:00:00Z[UTC]");
        ZonedDateTime dec24 = ZonedDateTime.parse("2021-12-24T11:00:00Z[UTC]");
        ZonedDateTime dec28 = ZonedDateTime.parse("2021-12-28T14:00:00Z[UTC]");

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
        assertThat(cashFlow.getCreated()).isEqualTo(FIXED_NOW);

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

        // Validate ALL 23 transactions with FULL object comparison (including time fields)
        // Using constructor instead of builder ensures compile-time safety when model changes
        Map<String, CashFlowDto.CashChangeSummaryJson> expectedTransactions = Map.ofEntries(
                // === July 2021 ===
                Map.entry(salaryJuly, new CashFlowDto.CashChangeSummaryJson(
                        salaryJuly, "July Salary", "Monthly salary payment",
                        Money.of(5000.0, "PLN"), Type.INFLOW, "Salary",
                        CashChangeStatus.CONFIRMED, FIXED_NOW, july15, july15)),
                Map.entry(rentJuly, new CashFlowDto.CashChangeSummaryJson(
                        rentJuly, "July Rent", "Monthly rent payment",
                        Money.of(1500.0, "PLN"), Type.OUTFLOW, "Rent",
                        CashChangeStatus.CONFIRMED, FIXED_NOW, july15, july15)),
                Map.entry(groceriesJuly, new CashFlowDto.CashChangeSummaryJson(
                        groceriesJuly, "Weekly groceries", "Biedronka shopping",
                        Money.of(250.0, "PLN"), Type.OUTFLOW, "Groceries",
                        CashChangeStatus.CONFIRMED, FIXED_NOW, july25, july25)),

                // === August 2021 ===
                Map.entry(salaryAug, new CashFlowDto.CashChangeSummaryJson(
                        salaryAug, "August Salary", "Monthly salary payment",
                        Money.of(5000.0, "PLN"), Type.INFLOW, "Salary",
                        CashChangeStatus.CONFIRMED, FIXED_NOW, aug10, aug10)),
                Map.entry(rentAug, new CashFlowDto.CashChangeSummaryJson(
                        rentAug, "August Rent", "Monthly rent payment",
                        Money.of(1500.0, "PLN"), Type.OUTFLOW, "Rent",
                        CashChangeStatus.CONFIRMED, FIXED_NOW, aug15, aug15)),
                Map.entry(utilitiesAug, new CashFlowDto.CashChangeSummaryJson(
                        utilitiesAug, "Electricity bill", "PGE electricity",
                        Money.of(180.0, "PLN"), Type.OUTFLOW, "Utilities",
                        CashChangeStatus.CONFIRMED, FIXED_NOW, aug20, aug20)),
                Map.entry(restaurantAug, new CashFlowDto.CashChangeSummaryJson(
                        restaurantAug, "Dinner out", "Restaurant visit",
                        Money.of(150.0, "PLN"), Type.OUTFLOW, "Restaurants",
                        CashChangeStatus.CONFIRMED, FIXED_NOW, aug20, aug20)),

                // === September 2021 ===
                Map.entry(salarySep, new CashFlowDto.CashChangeSummaryJson(
                        salarySep, "September Salary", "Monthly salary payment",
                        Money.of(5000.0, "PLN"), Type.INFLOW, "Salary",
                        CashChangeStatus.CONFIRMED, FIXED_NOW, sep5, sep5)),
                Map.entry(bonusSep, new CashFlowDto.CashChangeSummaryJson(
                        bonusSep, "Q3 Bonus", "Quarterly performance bonus",
                        Money.of(2000.0, "PLN"), Type.INFLOW, "Bonus",
                        CashChangeStatus.CONFIRMED, FIXED_NOW, sep28, sep28)),
                Map.entry(rentSep, new CashFlowDto.CashChangeSummaryJson(
                        rentSep, "September Rent", "Monthly rent payment",
                        Money.of(1500.0, "PLN"), Type.OUTFLOW, "Rent",
                        CashChangeStatus.CONFIRMED, FIXED_NOW, sep15, sep15)),
                Map.entry(transportSep, new CashFlowDto.CashChangeSummaryJson(
                        transportSep, "Fuel", "Orlen gas station",
                        Money.of(200.0, "PLN"), Type.OUTFLOW, "Transportation",
                        CashChangeStatus.CONFIRMED, FIXED_NOW, sep15, sep15)),

                // === October 2021 ===
                Map.entry(salaryOct, new CashFlowDto.CashChangeSummaryJson(
                        salaryOct, "October Salary", "Monthly salary payment",
                        Money.of(5200.0, "PLN"), Type.INFLOW, "Salary",
                        CashChangeStatus.CONFIRMED, FIXED_NOW, oct10, oct10)),
                Map.entry(rentOct, new CashFlowDto.CashChangeSummaryJson(
                        rentOct, "October Rent", "Monthly rent payment",
                        Money.of(1500.0, "PLN"), Type.OUTFLOW, "Rent",
                        CashChangeStatus.CONFIRMED, FIXED_NOW, oct10, oct10)),
                Map.entry(groceriesOct1, new CashFlowDto.CashChangeSummaryJson(
                        groceriesOct1, "Weekly groceries #1", "Lidl shopping",
                        Money.of(180.0, "PLN"), Type.OUTFLOW, "Groceries",
                        CashChangeStatus.CONFIRMED, FIXED_NOW, oct10, oct10)),
                Map.entry(groceriesOct2, new CashFlowDto.CashChangeSummaryJson(
                        groceriesOct2, "Weekly groceries #2", "Carrefour shopping",
                        Money.of(220.0, "PLN"), Type.OUTFLOW, "Groceries",
                        CashChangeStatus.CONFIRMED, FIXED_NOW, oct25, oct25)),

                // === November 2021 ===
                Map.entry(salaryNov, new CashFlowDto.CashChangeSummaryJson(
                        salaryNov, "November Salary", "Monthly salary payment",
                        Money.of(5200.0, "PLN"), Type.INFLOW, "Salary",
                        CashChangeStatus.CONFIRMED, FIXED_NOW, nov5, nov5)),
                Map.entry(rentNov, new CashFlowDto.CashChangeSummaryJson(
                        rentNov, "November Rent", "Monthly rent payment",
                        Money.of(1500.0, "PLN"), Type.OUTFLOW, "Rent",
                        CashChangeStatus.CONFIRMED, FIXED_NOW, nov15, nov15)),
                Map.entry(utilitiesNov, new CashFlowDto.CashChangeSummaryJson(
                        utilitiesNov, "Gas bill", "PGNiG gas",
                        Money.of(120.0, "PLN"), Type.OUTFLOW, "Utilities",
                        CashChangeStatus.CONFIRMED, FIXED_NOW, nov20, nov20)),

                // === December 2021 ===
                Map.entry(salaryDec, new CashFlowDto.CashChangeSummaryJson(
                        salaryDec, "December Salary", "Monthly salary payment",
                        Money.of(5200.0, "PLN"), Type.INFLOW, "Salary",
                        CashChangeStatus.CONFIRMED, FIXED_NOW, dec10, dec10)),
                Map.entry(bonusDec, new CashFlowDto.CashChangeSummaryJson(
                        bonusDec, "Christmas Bonus", "End of year bonus",
                        Money.of(3000.0, "PLN"), Type.INFLOW, "Bonus",
                        CashChangeStatus.CONFIRMED, FIXED_NOW, dec24, dec24)),
                Map.entry(rentDec, new CashFlowDto.CashChangeSummaryJson(
                        rentDec, "December Rent", "Monthly rent payment",
                        Money.of(1500.0, "PLN"), Type.OUTFLOW, "Rent",
                        CashChangeStatus.CONFIRMED, FIXED_NOW, dec15, dec15)),
                Map.entry(groceriesDec, new CashFlowDto.CashChangeSummaryJson(
                        groceriesDec, "Christmas shopping", "Holiday groceries",
                        Money.of(500.0, "PLN"), Type.OUTFLOW, "Groceries",
                        CashChangeStatus.CONFIRMED, FIXED_NOW, dec24, dec24)),
                Map.entry(restaurantDec, new CashFlowDto.CashChangeSummaryJson(
                        restaurantDec, "Christmas dinner", "Family dinner restaurant",
                        Money.of(400.0, "PLN"), Type.OUTFLOW, "Restaurants",
                        CashChangeStatus.CONFIRMED, FIXED_NOW, dec28, dec28))
        );

        // Validate each transaction with full recursive comparison
        for (Map.Entry<String, CashFlowDto.CashChangeSummaryJson> entry : expectedTransactions.entrySet()) {
            CashFlowDto.CashChangeSummaryJson actual = cashFlow.getCashChanges().get(entry.getKey());
            assertThat(actual)
                    .as("Transaction %s (%s)", entry.getValue().getName(), entry.getKey())
                    .usingRecursiveComparison()
                    .isEqualTo(entry.getValue());
        }

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
                "U10000006",
                uniqueCashFlowName(),
                YearMonth.of(2021, 7),
                Money.of(10000.0, "PLN")
        );

        // when: configure mappings with CREATE_NEW (MAP_TO_EXISTING was removed - only one file per CashFlow)
        actor.configureMappings(cashFlowId, List.of(
                actor.mappingCreateNewCategory("Zakupy kartą", "Groceries", Type.OUTFLOW),
                actor.mappingCreateNew("Streaming", "Entertainment", Type.OUTFLOW)
        ));

        // then: validate mappings response
        BankDataIngestionDto.GetMappingsResponse mappingsResponse = actor.getMappings(cashFlowId);
        assertThat(mappingsResponse.getCashFlowId()).isEqualTo(cashFlowId);
        assertThat(mappingsResponse.getMappingsCount()).isEqualTo(2);
        assertThat(mappingsResponse.getMappings()).hasSize(2);

        // Validate first mapping (CREATE_NEW) - only ignore generated fields
        BankDataIngestionDto.MappingJson groceriesMapping = mappingsResponse.getMappings().stream()
                .filter(m -> "Zakupy kartą".equals(m.getBankCategoryName()))
                .findFirst()
                .orElseThrow();

        assertThat(groceriesMapping.getMappingId()).isNotNull();
        assertThat(groceriesMapping.getBankCategoryName()).isEqualTo("Zakupy kartą");
        assertThat(groceriesMapping.getAction()).isEqualTo(MappingAction.CREATE_NEW);
        assertThat(groceriesMapping.getTargetCategoryName()).isEqualTo("Groceries");
        assertThat(groceriesMapping.getParentCategoryName()).isNull();
        assertThat(groceriesMapping.getCategoryType()).isEqualTo(Type.OUTFLOW);
        assertThat(groceriesMapping.getCreatedAt()).isEqualTo(FIXED_NOW);

        // Validate second mapping (CREATE_NEW)
        BankDataIngestionDto.MappingJson streamingMapping = mappingsResponse.getMappings().stream()
                .filter(m -> "Streaming".equals(m.getBankCategoryName()))
                .findFirst()
                .orElseThrow();

        assertThat(streamingMapping.getMappingId()).isNotNull();
        assertThat(streamingMapping.getBankCategoryName()).isEqualTo("Streaming");
        assertThat(streamingMapping.getAction()).isEqualTo(MappingAction.CREATE_NEW);
        assertThat(streamingMapping.getTargetCategoryName()).isEqualTo("Entertainment");
        assertThat(streamingMapping.getParentCategoryName()).isNull();
        assertThat(streamingMapping.getCategoryType()).isEqualTo(Type.OUTFLOW);
        assertThat(streamingMapping.getCreatedAt()).isEqualTo(FIXED_NOW);

        log.info("Mappings validated: {} -> {} ({}), {} -> {} ({})",
                groceriesMapping.getBankCategoryName(), groceriesMapping.getTargetCategoryName(), groceriesMapping.getAction(),
                streamingMapping.getBankCategoryName(), streamingMapping.getTargetCategoryName(), streamingMapping.getAction());
    }

    @Test
    @DisplayName("Should list active staging sessions via REST API - verifies GET /bank-data-ingestion/{cashFlowId}/staging")
    void shouldListActiveStagingSessionsViaRestApi() {
        // given - create CashFlow
        YearMonth startPeriod = YearMonth.of(2021, 7);

        String cashFlowId = actor.createCashFlowWithHistory(
                "U10000006",
                uniqueCashFlowName(),
                startPeriod,
                Money.of(10000.0, "PLN")
        );

        // Configure mappings
        actor.configureMappings(cashFlowId, List.of(
                actor.mappingCreateNew("Zakupy", "Groceries", Type.OUTFLOW)
        ));

        // Stage transactions
        BankDataIngestionDto.StageTransactionsResponse stageResponse = actor.stageTransactions(cashFlowId, List.of(
                actor.bankTransaction("txn-001", "Biedronka", "Zakupy", 150.50, "PLN", Type.OUTFLOW,
                        ZonedDateTime.of(2021, 8, 15, 10, 0, 0, 0, ZoneOffset.UTC)),
                actor.bankTransaction("txn-002", "Lidl", "Zakupy", 89.99, "PLN", Type.OUTFLOW,
                        ZonedDateTime.of(2021, 8, 20, 10, 0, 0, 0, ZoneOffset.UTC))
        ));

        String stagingSessionId = stageResponse.getStagingSessionId();

        // when - list staging sessions
        BankDataIngestionDto.ListStagingSessionsResponse response = actor.listStagingSessions(cashFlowId);

        // then - validate response
        assertThat(response.getCashFlowId()).isEqualTo(cashFlowId);
        assertThat(response.isHasPendingImport()).isTrue();
        assertThat(response.getStagingSessions()).hasSize(1);

        BankDataIngestionDto.StagingSessionSummaryJson session = response.getStagingSessions().get(0);
        assertThat(session.getStagingSessionId()).isEqualTo(stagingSessionId);
        assertThat(session.getStatus()).isEqualTo("READY_FOR_IMPORT");
        assertThat(session.getCounts().getTotalTransactions()).isEqualTo(2);
        assertThat(session.getCounts().getValidTransactions()).isEqualTo(2);
        assertThat(session.getCounts().getInvalidTransactions()).isEqualTo(0);
        assertThat(session.getCounts().getDuplicateTransactions()).isEqualTo(0);
        assertThat(session.getCreatedAt()).isEqualTo(FIXED_NOW);
        assertThat(session.getExpiresAt()).isAfter(session.getCreatedAt());

        log.info("Listed staging session: id={}, status={}, transactions={}",
                session.getStagingSessionId(), session.getStatus(), session.getCounts().getTotalTransactions());
    }

    @Test
    @DisplayName("Should return empty list when no active staging sessions via REST API")
    void shouldReturnEmptyListWhenNoStagingSessionsViaRestApi() {
        // given - create CashFlow without any staging
        YearMonth startPeriod = YearMonth.of(2021, 7);

        String cashFlowId = actor.createCashFlowWithHistory(
                "U10000006",
                uniqueCashFlowName(),
                startPeriod,
                Money.of(10000.0, "PLN")
        );

        // when - list staging sessions
        BankDataIngestionDto.ListStagingSessionsResponse response = actor.listStagingSessions(cashFlowId);

        // then - verify empty response
        assertThat(response.getCashFlowId()).isEqualTo(cashFlowId);
        assertThat(response.isHasPendingImport()).isFalse();
        assertThat(response.getStagingSessions()).isEmpty();

        log.info("No active staging sessions found for CashFlow {}", cashFlowId);
    }

    // ============ CSV Upload Tests ============

    @Test
    @DisplayName("Should upload CSV file from resources and stage transactions via REST API")
    void shouldUploadCsvFileFromResourcesAndStageTransactions() {
        // given - create CashFlow with 6 months history
        YearMonth startPeriod = YearMonth.of(2021, 7);

        String cashFlowId = actor.createCashFlowWithHistory(
                "U10000006",
                uniqueCashFlowName(),
                startPeriod,
                Money.of(10000.0, "PLN")
        );

        // Configure mappings for bank categories in the CSV file
        actor.configureMappings(cashFlowId, List.of(
                actor.mappingCreateNew("Wpływy regularne", "Salary", Type.INFLOW),
                actor.mappingCreateNew("Mieszkanie", "Rent", Type.OUTFLOW),
                actor.mappingCreateNew("Zakupy kartą", "Groceries", Type.OUTFLOW),
                actor.mappingCreateNew("Rachunki", "Utilities", Type.OUTFLOW),
                actor.mappingCreateNew("Rozrywka", "Entertainment", Type.OUTFLOW),
                actor.mappingCreateNew("Transport", "Transportation", Type.OUTFLOW)
        ));

        // when - upload CSV file from resources
        BankDataIngestionDto.UploadCsvResponse uploadResponse = actor.uploadCsv(
                cashFlowId,
                "bank-data-ingestion/historical-transactions.csv"
        );

        // then - verify parse summary (23 transactions in the CSV file)
        assertThat(uploadResponse.getParseSummary().getTotalRows()).isEqualTo(23);
        assertThat(uploadResponse.getParseSummary().getSuccessfulRows()).isEqualTo(23);
        assertThat(uploadResponse.getParseSummary().getFailedRows()).isEqualTo(0);
        assertThat(uploadResponse.getParseSummary().getErrors()).isEmpty();

        // verify staging result
        assertThat(uploadResponse.getStagingResult()).isNotNull();
        assertThat(uploadResponse.getStagingResult().getStatus()).isEqualTo("READY_FOR_IMPORT");
        assertThat(uploadResponse.getStagingResult().getSummary().getTotalTransactions()).isEqualTo(23);
        assertThat(uploadResponse.getStagingResult().getSummary().getValidTransactions()).isEqualTo(23);
        assertThat(uploadResponse.getStagingResult().getSummary().getInvalidTransactions()).isEqualTo(0);
        assertThat(uploadResponse.getStagingResult().getSummary().getDuplicateTransactions()).isEqualTo(0);
        assertThat(uploadResponse.getStagingResult().getUnmappedCategories()).isEmpty();

        String stagingSessionId = uploadResponse.getStagingResult().getStagingSessionId();
        assertThat(stagingSessionId).isNotNull();

        // Verify staging preview
        BankDataIngestionDto.GetStagingPreviewResponse preview = actor.getStagingPreview(cashFlowId, stagingSessionId);
        assertThat(preview.getSummary().getTotalTransactions()).isEqualTo(23);

        // Calculate expected totals from CSV
        // INFLOW: 5000 + 5000 + 5000 + 2000 + 5200 + 5200 + 5200 + 3000 = 35600
        // OUTFLOW: 1500 + 250 + 1500 + 180 + 150 + 1500 + 200 + 1500 + 180 + 220 + 1500 + 120 + 1500 + 500 + 400 = 11200
        double totalInflow = preview.getTransactions().stream()
                .filter(tx -> tx.getType() == Type.INFLOW)
                .mapToDouble(tx -> tx.getAmount())
                .sum();
        double totalOutflow = preview.getTransactions().stream()
                .filter(tx -> tx.getType() == Type.OUTFLOW)
                .mapToDouble(tx -> tx.getAmount())
                .sum();

        assertThat(totalInflow).isEqualTo(35600.0);
        assertThat(totalOutflow).isEqualTo(11200.0);

        log.info("CSV upload successful: {} transactions staged, total inflow={} PLN, total outflow={} PLN",
                preview.getSummary().getTotalTransactions(), totalInflow, totalOutflow);
    }

    @Test
    @DisplayName("Should upload CSV file and detect unmapped categories via REST API")
    void shouldUploadCsvFileAndDetectUnmappedCategoriesViaRestApi() {
        // given - create CashFlow WITHOUT configuring all mappings
        YearMonth startPeriod = YearMonth.of(2021, 7);

        String cashFlowId = actor.createCashFlowWithHistory(
                "U10000006",
                uniqueCashFlowName(),
                startPeriod,
                Money.of(10000.0, "PLN")
        );

        // Configure only some mappings (missing: Rozrywka, Transport)
        actor.configureMappings(cashFlowId, List.of(
                actor.mappingCreateNew("Wpływy regularne", "Salary", Type.INFLOW),
                actor.mappingCreateNew("Mieszkanie", "Rent", Type.OUTFLOW),
                actor.mappingCreateNew("Zakupy kartą", "Groceries", Type.OUTFLOW),
                actor.mappingCreateNew("Rachunki", "Utilities", Type.OUTFLOW)
                // Missing: Rozrywka, Transport
        ));

        // when - upload CSV file from resources
        BankDataIngestionDto.UploadCsvResponse uploadResponse = actor.uploadCsv(
                cashFlowId,
                "bank-data-ingestion/historical-transactions.csv"
        );

        // then - CSV should be parsed successfully
        assertThat(uploadResponse.getParseSummary().getSuccessfulRows()).isEqualTo(23);

        // But staging should detect unmapped categories
        assertThat(uploadResponse.getStagingResult()).isNotNull();
        assertThat(uploadResponse.getStagingResult().getStatus()).isEqualTo("HAS_UNMAPPED_CATEGORIES");
        assertThat(uploadResponse.getStagingResult().getUnmappedCategories()).hasSize(2);

        List<String> unmappedBankCategories = uploadResponse.getStagingResult().getUnmappedCategories().stream()
                .map(BankDataIngestionDto.UnmappedCategoryJson::getBankCategory)
                .toList();
        assertThat(unmappedBankCategories).containsExactlyInAnyOrder("Rozrywka", "Transport");

        log.info("CSV upload detected {} unmapped categories: {}",
                uploadResponse.getStagingResult().getUnmappedCategories().size(),
                unmappedBankCategories);
    }

    @Test
    @DisplayName("Should complete full import flow using CSV file from resources via REST API")
    void shouldCompleteFullImportFlowUsingCsvFileViaRestApi() {
        // given - create CashFlow
        YearMonth startPeriod = YearMonth.of(2021, 7);

        String cashFlowId = actor.createCashFlowWithHistory(
                "U10000006",
                uniqueCashFlowName(),
                startPeriod,
                Money.of(10000.0, "PLN")
        );

        // Configure all mappings
        actor.configureMappings(cashFlowId, List.of(
                actor.mappingCreateNew("Wpływy regularne", "Salary", Type.INFLOW),
                actor.mappingCreateNew("Mieszkanie", "Rent", Type.OUTFLOW),
                actor.mappingCreateNew("Zakupy kartą", "Groceries", Type.OUTFLOW),
                actor.mappingCreateNew("Rachunki", "Utilities", Type.OUTFLOW),
                actor.mappingCreateNew("Rozrywka", "Entertainment", Type.OUTFLOW),
                actor.mappingCreateNew("Transport", "Transportation", Type.OUTFLOW)
        ));

        // Step 1: Upload CSV
        BankDataIngestionDto.UploadCsvResponse uploadResponse = actor.uploadCsv(
                cashFlowId,
                "bank-data-ingestion/historical-transactions.csv"
        );

        assertThat(uploadResponse.getStagingResult().getStatus()).isEqualTo("READY_FOR_IMPORT");
        String stagingSessionId = uploadResponse.getStagingResult().getStagingSessionId();

        // Step 2: Start import (may complete immediately for small datasets)
        BankDataIngestionDto.StartImportResponse importResponse = actor.startImport(cashFlowId, stagingSessionId);
        assertThat(importResponse.getStatus()).isIn("IN_PROGRESS", "COMPLETED");
        String jobId = importResponse.getJobId();

        // Step 3: Check progress (should complete quickly in test)
        BankDataIngestionDto.GetImportProgressResponse progress = actor.getImportProgress(cashFlowId, jobId);
        // Import should be completed with all transactions processed
        assertThat(progress.getResult()).isNotNull();
        assertThat(progress.getResult().getTransactionsImported()).isEqualTo(23);
        assertThat(progress.getResult().getTransactionsFailed()).isEqualTo(0);

        // Step 4: Finalize import
        BankDataIngestionDto.FinalizeImportResponse finalizeResponse = actor.finalizeImport(cashFlowId, jobId, false);
        assertThat(finalizeResponse.getStatus()).isIn("COMPLETED", "FINALIZED");

        // Step 5: Verify CashFlow has all transactions
        CashFlowDto.CashFlowSummaryJson cashFlow = actor.getCashFlow(cashFlowId);
        assertThat(cashFlow.getCashChanges()).hasSize(23);

        // Verify categories were created
        assertThat(cashFlow.getInflowCategories().stream()
                .map(c -> c.getCategoryName().name())
                .toList()).contains("Salary");

        assertThat(cashFlow.getOutflowCategories().stream()
                .map(c -> c.getCategoryName().name())
                .toList()).containsAll(List.of("Rent", "Groceries", "Utilities", "Entertainment", "Transportation"));

        log.info("Full import flow completed: {} transactions imported", cashFlow.getCashChanges().size());
    }

    @Test
    @DisplayName("Should upload CSV with unmapped categories, configure mappings, revalidate, and complete import via REST API")
    void shouldUploadWithUnmappedCategoriesThenRevalidateAndImportViaRestApi() {
        // given - create CashFlow WITHOUT configuring all mappings upfront
        YearMonth startPeriod = YearMonth.of(2021, 7);

        String cashFlowId = actor.createCashFlowWithHistory(
                "U10000006",
                uniqueCashFlowName(),
                startPeriod,
                Money.of(10000.0, "PLN")
        );

        // Step 1: Upload CSV WITHOUT any mappings configured
        BankDataIngestionDto.UploadCsvResponse uploadResponse = actor.uploadCsv(
                cashFlowId,
                "bank-data-ingestion/historical-transactions.csv"
        );

        // then - CSV parsed successfully but staging has unmapped categories
        assertThat(uploadResponse.getParseSummary().getSuccessfulRows()).isEqualTo(23);
        assertThat(uploadResponse.getStagingResult()).isNotNull();
        assertThat(uploadResponse.getStagingResult().getStatus()).isEqualTo("HAS_UNMAPPED_CATEGORIES");
        assertThat(uploadResponse.getStagingResult().getUnmappedCategories()).hasSize(6);

        String stagingSessionId = uploadResponse.getStagingResult().getStagingSessionId();
        log.info("Staging session {} has {} unmapped categories",
                stagingSessionId, uploadResponse.getStagingResult().getUnmappedCategories().size());

        // Step 2: List staging sessions - should show active session with pending status
        BankDataIngestionDto.ListStagingSessionsResponse sessionsBeforeMappings = actor.listStagingSessions(cashFlowId);
        assertThat(sessionsBeforeMappings.isHasPendingImport()).isTrue();
        assertThat(sessionsBeforeMappings.getStagingSessions()).hasSize(1);
        // ListStagingSessionsQuery returns PENDING_REVIEW when transactions have PENDING_MAPPING status
        // (as opposed to StageTransactions which returns HAS_UNMAPPED_CATEGORIES)
        assertThat(sessionsBeforeMappings.getStagingSessions().get(0).getStatus()).isEqualTo("PENDING_REVIEW");

        // Step 3: Configure ALL mappings
        actor.configureMappings(cashFlowId, List.of(
                actor.mappingCreateNew("Wpływy regularne", "Salary", Type.INFLOW),
                actor.mappingCreateNew("Mieszkanie", "Rent", Type.OUTFLOW),
                actor.mappingCreateNew("Zakupy kartą", "Groceries", Type.OUTFLOW),
                actor.mappingCreateNew("Rachunki", "Utilities", Type.OUTFLOW),
                actor.mappingCreateNew("Rozrywka", "Entertainment", Type.OUTFLOW),
                actor.mappingCreateNew("Transport", "Transportation", Type.OUTFLOW)
        ));

        log.info("Configured all 6 mappings for CashFlow {}", cashFlowId);

        // Step 4: Revalidate staging session - transactions should now be validated with new mappings
        BankDataIngestionDto.RevalidateStagingResponse revalidateResponse = actor.revalidateStaging(cashFlowId, stagingSessionId);

        // RevalidateStaging returns SUCCESS when all transactions now have mappings
        assertThat(revalidateResponse.getStatus()).isEqualTo("SUCCESS");
        assertThat(revalidateResponse.getStillUnmappedCategories()).isEmpty();
        assertThat(revalidateResponse.getSummary().getTotalTransactions()).isEqualTo(23);
        assertThat(revalidateResponse.getSummary().getRevalidatedCount()).isGreaterThan(0);
        assertThat(revalidateResponse.getSummary().getStillPendingCount()).isEqualTo(0);
        assertThat(revalidateResponse.getSummary().getValidCount()).isEqualTo(23);

        log.info("Revalidation complete: {} transactions revalidated, {} valid, {} still pending",
                revalidateResponse.getSummary().getRevalidatedCount(),
                revalidateResponse.getSummary().getValidCount(),
                revalidateResponse.getSummary().getStillPendingCount());

        // Step 5: List staging sessions again - should now show READY_FOR_IMPORT
        BankDataIngestionDto.ListStagingSessionsResponse sessionsAfterRevalidation = actor.listStagingSessions(cashFlowId);
        assertThat(sessionsAfterRevalidation.getStagingSessions().get(0).getStatus()).isEqualTo("READY_FOR_IMPORT");
        assertThat(sessionsAfterRevalidation.getStagingSessions().get(0).getCounts().getValidTransactions()).isEqualTo(23);

        // Step 6: Start import
        BankDataIngestionDto.StartImportResponse importResponse = actor.startImport(cashFlowId, stagingSessionId);
        assertThat(importResponse.getStatus()).isIn("IN_PROGRESS", "COMPLETED");
        String jobId = importResponse.getJobId();

        // Step 7: Check progress - should complete
        BankDataIngestionDto.GetImportProgressResponse progress = actor.getImportProgress(cashFlowId, jobId);
        assertThat(progress.getResult()).isNotNull();
        assertThat(progress.getResult().getTransactionsImported()).isEqualTo(23);
        assertThat(progress.getResult().getTransactionsFailed()).isEqualTo(0);

        // Verify import summary is present (summary may not always be populated)
        if (progress.getSummary() != null) {
            assertThat(progress.getSummary().getCategoryBreakdown()).isNotEmpty();
            // totalDurationMs may be 0 in tests due to fast execution
            assertThat(progress.getSummary().getTotalDurationMs()).isGreaterThanOrEqualTo(0);
        }

        // Step 8: Finalize import
        BankDataIngestionDto.FinalizeImportResponse finalizeResponse = actor.finalizeImport(cashFlowId, jobId, false);
        assertThat(finalizeResponse.getStatus()).isIn("COMPLETED", "FINALIZED");

        // Step 9: Verify CashFlow has all transactions and categories
        CashFlowDto.CashFlowSummaryJson cashFlow = actor.getCashFlow(cashFlowId);
        assertThat(cashFlow.getCashChanges()).hasSize(23);

        // Verify categories were created (6 categories mapped during import)
        assertThat(cashFlow.getInflowCategories().stream()
                .map(c -> c.getCategoryName().name())
                .toList()).contains("Salary");

        assertThat(cashFlow.getOutflowCategories().stream()
                .map(c -> c.getCategoryName().name())
                .toList()).containsAll(List.of("Rent", "Groceries", "Utilities", "Entertainment", "Transportation"));

        log.info("Full revalidation import flow completed: {} transactions imported with {} categories",
                cashFlow.getCashChanges().size(),
                cashFlow.getInflowCategories().size() + cashFlow.getOutflowCategories().size() - 2); // minus system Uncategorized
    }

    // ============ OPEN Mode Import Tests (Post-Attestation) ============

    @Test
    @DisplayName("Should import transactions in OPEN mode after attestation via REST API")
    void shouldImportTransactionsInOpenModeAfterAttestationViaRestApi() {
        // given - create CashFlow with history, import some transactions, then attest

        // Start period: July 2021
        // Active period: January 2022 (fixed clock)
        // Historical months: July 2021 - December 2021 (6 months)
        YearMonth startPeriod = YearMonth.of(2021, 7);

        String cashFlowId = actor.createCashFlowWithHistory(
                "U10000007",
                "Test CashFlow for OPEN Mode",
                startPeriod,
                Money.of(10000.0, "PLN")
        );

        // Configure mappings
        actor.configureMappings(cashFlowId, List.of(
                actor.mappingCreateNew("Wpływy regularne", "Salary", Type.INFLOW),
                actor.mappingCreateNew("Mieszkanie", "Rent", Type.OUTFLOW),
                actor.mappingCreateNew("Zakupy kartą", "Groceries", Type.OUTFLOW),
                actor.mappingCreateNew("Rachunki", "Utilities", Type.OUTFLOW),
                actor.mappingCreateNew("Rozrywka", "Entertainment", Type.OUTFLOW),
                actor.mappingCreateNew("Transport", "Transportation", Type.OUTFLOW)
        ));

        // Step 1: Upload CSV in SETUP mode (import initial historical data)
        BankDataIngestionDto.UploadCsvResponse uploadSetup = actor.uploadCsv(
                cashFlowId,
                "bank-data-ingestion/historical-transactions.csv"
        );
        assertThat(uploadSetup.getStagingResult().getStatus()).isEqualTo("READY_FOR_IMPORT");

        String stagingSessionId = uploadSetup.getStagingResult().getStagingSessionId();

        // Step 2: Start and complete import
        BankDataIngestionDto.StartImportResponse importResponse = actor.startImport(cashFlowId, stagingSessionId);
        String jobId = importResponse.getJobId();

        BankDataIngestionDto.GetImportProgressResponse progress = actor.getImportProgress(cashFlowId, jobId);
        assertThat(progress.getResult().getTransactionsImported()).isEqualTo(23);

        actor.finalizeImport(cashFlowId, jobId, false);

        // Verify SETUP mode state
        CashFlowDto.CashFlowSummaryJson cashFlowBefore = actor.getCashFlow(cashFlowId);
        assertThat(cashFlowBefore.getStatus()).isEqualTo(CashFlow.CashFlowStatus.SETUP);
        assertThat(cashFlowBefore.getCashChanges()).hasSize(23);

        // Calculate expected balance after initial import
        // Initial balance: 10000 PLN
        // INFLOW: 5000 + 5000 + 5000 + 2000 + 5200 + 5200 + 5200 + 3000 = 35600
        // OUTFLOW: 1500 + 250 + 1500 + 180 + 150 + 1500 + 200 + 1500 + 180 + 220 + 1500 + 120 + 1500 + 500 + 400 = 11200
        // Net: 35600 - 11200 = 24400
        // Expected balance: 10000 + 24400 = 34400
        Money expectedBalance = Money.of(34400.0, "PLN");

        // Step 3: Attest historical import - transitions to OPEN mode
        CashFlowDto.AttestHistoricalImportResponseJson attestResponse = actor.attestHistoricalImport(
                cashFlowId, expectedBalance, false, false);

        assertThat(attestResponse.getStatus()).isEqualTo(CashFlow.CashFlowStatus.OPEN);
        assertThat(attestResponse.getConfirmedBalance().getAmount()).isEqualByComparingTo(expectedBalance.getAmount());

        // Verify CashFlow is now in OPEN mode
        CashFlowDto.CashFlowSummaryJson cashFlowAfterAttestation = actor.getCashFlow(cashFlowId);
        assertThat(cashFlowAfterAttestation.getStatus()).isEqualTo(CashFlow.CashFlowStatus.OPEN);

        log.info("CashFlow {} attested successfully. Status: {}", cashFlowId, cashFlowAfterAttestation.getStatus());

        // Step 4: NOW TEST THE BUG FIX - Upload more transactions in OPEN mode
        // These transactions target the ACTIVE month (January 2022)
        // The bug was: import failed because monthStatuses was not populated
        // Note: Fixed clock is set to 2022-01-01, so transaction dates must be <= 2022-01-01
        // We use 2022-01-01 which is in the ACTIVE month (January 2022)

        // Create a small CSV with transactions for the ACTIVE month (2022-01)
        String activeMonthCsv = """
                bankTransactionId,name,description,bankCategory,amount,currency,type,operationDate,bookingDate,sourceAccountNumber,targetAccountNumber
                TXN-OPEN-001,January Salary,Monthly salary 2022,Wpływy regularne,5500.00,PLN,INFLOW,2022-01-01,2022-01-01,,PL12345678901234567890123456
                TXN-OPEN-002,January Rent,Monthly rent 2022,Mieszkanie,1600.00,PLN,OUTFLOW,2022-01-01,2022-01-01,PL12345678901234567890123456,PL98765432109876543210987654
                TXN-OPEN-003,January Groceries,Weekly shopping,Zakupy kartą,350.00,PLN,OUTFLOW,2022-01-01,2022-01-01,PL12345678901234567890123456,
                """;

        BankDataIngestionDto.UploadCsvResponse uploadOpen = actor.uploadCsvContent(
                cashFlowId,
                "open-mode-transactions.csv",
                activeMonthCsv.getBytes()
        );

        // Verify the upload was successful (this was the bug - it would fail with "outside forecast range")
        assertThat(uploadOpen.getParseSummary().getTotalRows()).isEqualTo(3);
        assertThat(uploadOpen.getParseSummary().getSuccessfulRows()).isEqualTo(3);
        assertThat(uploadOpen.getStagingResult()).isNotNull();

        // Log staging result for debugging
        log.info("OPEN mode staging result: status={}, validTransactions={}, invalidTransactions={}, unmappedCategories={}",
                uploadOpen.getStagingResult().getStatus(),
                uploadOpen.getStagingResult().getSummary().getValidTransactions(),
                uploadOpen.getStagingResult().getSummary().getInvalidTransactions(),
                uploadOpen.getStagingResult().getUnmappedCategories());

        assertThat(uploadOpen.getStagingResult().getStatus())
                .as("Expected READY_FOR_IMPORT but got %s with unmapped categories: %s, monthly breakdown: %s",
                        uploadOpen.getStagingResult().getStatus(),
                        uploadOpen.getStagingResult().getUnmappedCategories(),
                        uploadOpen.getStagingResult().getMonthlyBreakdown())
                .isEqualTo("READY_FOR_IMPORT");
        assertThat(uploadOpen.getStagingResult().getSummary().getValidTransactions()).isEqualTo(3);

        log.info("OPEN mode CSV upload successful: {} transactions staged",
                uploadOpen.getStagingResult().getSummary().getTotalTransactions());

        // Step 5: Import the OPEN mode transactions
        String openStagingSessionId = uploadOpen.getStagingResult().getStagingSessionId();
        BankDataIngestionDto.StartImportResponse openImportResponse = actor.startImport(cashFlowId, openStagingSessionId);
        String openJobId = openImportResponse.getJobId();

        BankDataIngestionDto.GetImportProgressResponse openProgress = actor.getImportProgress(cashFlowId, openJobId);
        assertThat(openProgress.getResult().getTransactionsImported()).isEqualTo(3);
        assertThat(openProgress.getResult().getTransactionsFailed()).isEqualTo(0);

        actor.finalizeImport(cashFlowId, openJobId, false);

        // Step 6: Verify final state
        CashFlowDto.CashFlowSummaryJson finalCashFlow = actor.getCashFlow(cashFlowId);
        assertThat(finalCashFlow.getStatus()).isEqualTo(CashFlow.CashFlowStatus.OPEN);
        assertThat(finalCashFlow.getCashChanges()).hasSize(26); // 23 + 3

        // Verify the new transactions were imported
        boolean hasJanuarySalary = finalCashFlow.getCashChanges().values().stream()
                .anyMatch(tx -> "January Salary".equals(tx.getName()));
        boolean hasJanuaryRent = finalCashFlow.getCashChanges().values().stream()
                .anyMatch(tx -> "January Rent".equals(tx.getName()));
        boolean hasJanuaryGroceries = finalCashFlow.getCashChanges().values().stream()
                .anyMatch(tx -> "January Groceries".equals(tx.getName()));

        assertThat(hasJanuarySalary).as("January Salary transaction should be imported").isTrue();
        assertThat(hasJanuaryRent).as("January Rent transaction should be imported").isTrue();
        assertThat(hasJanuaryGroceries).as("January Groceries transaction should be imported").isTrue();

        log.info("OPEN mode import test completed successfully:");
        log.info("  - Initial import (SETUP mode): 23 transactions");
        log.info("  - Post-attestation import (OPEN mode): 3 transactions");
        log.info("  - Total transactions: {}", finalCashFlow.getCashChanges().size());
    }

    // ============ Invalid Transactions Preview Tests ============

    @Test
    @DisplayName("Should return invalid transactions with validation errors in staging preview via REST API")
    void shouldReturnInvalidTransactionsWithValidationErrorsInStagingPreview() {
        // given - create CashFlow with 6 months history (2021-07 to 2021-12)
        YearMonth startPeriod = YearMonth.of(2021, 7);

        String cashFlowId = actor.createCashFlowWithHistory(
                "U10000008",
                uniqueCashFlowName(),
                startPeriod,
                Money.of(10000.0, "PLN")
        );

        // Configure mappings
        actor.configureMappings(cashFlowId, List.of(
                actor.mappingCreateNew("Wpływy regularne", "Salary", Type.INFLOW),
                actor.mappingCreateNew("Mieszkanie", "Rent", Type.OUTFLOW)
        ));

        // when - upload CSV file with invalid transactions
        // The CSV contains:
        // - TXN-2021-08-001: Valid transaction (August 2021)
        // - TXN-2021-08-002: Valid transaction (August 2021)
        // - TXN-2020-01-001: Too old transaction (before start period 2021-07)
        BankDataIngestionDto.UploadCsvResponse uploadResponse = actor.uploadCsv(
                cashFlowId,
                "bank-data-ingestion/transactions-with-invalid.csv"
        );

        // then - verify staging result
        assertThat(uploadResponse.getParseSummary().getTotalRows()).isEqualTo(3);
        assertThat(uploadResponse.getParseSummary().getSuccessfulRows()).isEqualTo(3);
        assertThat(uploadResponse.getStagingResult()).isNotNull();

        // Log the actual staging result for debugging
        log.info("Staging result: status={}, valid={}, invalid={}, duplicate={}",
                uploadResponse.getStagingResult().getStatus(),
                uploadResponse.getStagingResult().getSummary().getValidTransactions(),
                uploadResponse.getStagingResult().getSummary().getInvalidTransactions(),
                uploadResponse.getStagingResult().getSummary().getDuplicateTransactions());

        String stagingSessionId = uploadResponse.getStagingResult().getStagingSessionId();

        // Get staging preview - THIS IS THE KEY ASSERTION
        // After the fix, all transactions (including invalid ones) should be returned
        BankDataIngestionDto.GetStagingPreviewResponse preview = actor.getStagingPreview(cashFlowId, stagingSessionId);

        // Log all transactions for debugging
        for (BankDataIngestionDto.StagedTransactionPreviewJson txn : preview.getTransactions()) {
            log.info("Transaction: bankId={}, name={}, status={}, errors={}, targetCategory={}",
                    txn.getBankTransactionId(),
                    txn.getName(),
                    txn.getValidation().getStatus(),
                    txn.getValidation().getErrors(),
                    txn.getTargetCategory());
        }

        // Verify ALL 3 transactions are returned (not just valid ones)
        assertThat(preview.getTransactions())
                .as("All transactions including invalid ones should be returned in preview")
                .hasSize(3);

        // Find the invalid transaction (too old - before start period)
        BankDataIngestionDto.StagedTransactionPreviewJson oldTxn = preview.getTransactions().stream()
                .filter(t -> "TXN-2020-01-001".equals(t.getBankTransactionId()))
                .findFirst()
                .orElse(null);

        // KEY TEST: After the fix, invalid transaction should be in the preview
        // Before the fix, it would be filtered out because mappedData was null
        assertThat(oldTxn)
                .as("Invalid transaction (TXN-2020-01-001) should be present in preview - " +
                        "this was the bug: transactions with null mappedData were filtered out")
                .isNotNull();

        // Verify the invalid transaction has its original data available
        assertThat(oldTxn.getName()).isEqualTo("Too Old Transaction");
        assertThat(oldTxn.getAmount()).isEqualTo(2000.0);
        assertThat(oldTxn.getBankCategory()).isEqualTo("Wpływy regularne");

        // Verify validation errors are present
        assertThat(oldTxn.getValidation().getErrors())
                .as("Invalid transaction should have validation errors explaining why it was rejected")
                .isNotEmpty();

        log.info("Invalid transaction found in preview:");
        log.info("  - bankTransactionId: {}", oldTxn.getBankTransactionId());
        log.info("  - name: {}", oldTxn.getName());
        log.info("  - amount: {}", oldTxn.getAmount());
        log.info("  - status: {}", oldTxn.getValidation().getStatus());
        log.info("  - errors: {}", oldTxn.getValidation().getErrors());
        log.info("  - targetCategory: {} (expected null for invalid txn)", oldTxn.getTargetCategory());

        // Verify valid transactions are also present
        long validCount = preview.getTransactions().stream()
                .filter(t -> "VALID".equals(t.getValidation().getStatus()))
                .count();

        log.info("Summary: {} valid transactions, {} total in preview",
                validCount, preview.getTransactions().size());
    }
}
