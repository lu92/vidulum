package com.multi.vidulum.bank_data_ingestion.app;

import com.multi.vidulum.bank_data_ingestion.infrastructure.CategoryMappingMongoRepository;
import com.multi.vidulum.bank_data_ingestion.infrastructure.ImportJobMongoRepository;
import com.multi.vidulum.bank_data_ingestion.infrastructure.StagedTransactionMongoRepository;
import com.multi.vidulum.cashflow.app.CashFlowDto;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.cashflow.infrastructure.CashFlowMongoRepository;
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
        String cashFlowId = actor.createCashFlowWithHistory(
                "test-user-123",
                "Test CashFlow",
                YearMonth.of(2021, 7),
                Money.of(10000.0, "PLN")
        );

        // when
        Map<String, Object> cashFlowInfo = actor.getCashFlowAsMap(cashFlowId);

        // then
        assertThat(cashFlowInfo.get("cashFlowId")).isEqualTo(cashFlowId);
        assertThat(cashFlowInfo.get("status")).isEqualTo("SETUP");

        log.info("CashFlow info retrieved via REST API: cashFlowId={}, status={}",
                cashFlowInfo.get("cashFlowId"), cashFlowInfo.get("status"));
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
        boolean categoryExists = cashFlow.getOutflowCategories().stream()
                .anyMatch(cat -> "NewCategory".equals(cat.getCategoryName().name()));
        assertThat(categoryExists).isTrue();

        log.info("Category created and verified via REST API");
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
        var parentCategory = cashFlow.getOutflowCategories().stream()
                .filter(cat -> "ParentCategory".equals(cat.getCategoryName().name()))
                .findFirst()
                .orElse(null);

        assertThat(parentCategory).isNotNull();
        assertThat(parentCategory.getSubCategories()).isNotEmpty();

        boolean childExists = parentCategory.getSubCategories().stream()
                .anyMatch(cat -> "ChildCategory".equals(cat.getCategoryName().name()));
        assertThat(childExists).isTrue();

        log.info("Subcategory created and verified via REST API");
    }

    @Test
    @DisplayName("Should import historical transaction via REST API - verifies POST /cash-flow/{id}/import-historical")
    void shouldImportHistoricalTransactionViaRestApi() {
        // given
        String cashFlowId = actor.createCashFlowWithHistory(
                "test-user-123",
                "Test CashFlow",
                YearMonth.of(2021, 7),
                Money.of(10000.0, "PLN")
        );
        actor.createCategory(cashFlowId, "TestCategory", Type.OUTFLOW);

        // when
        ZonedDateTime transactionDate = ZonedDateTime.of(2021, 8, 15, 12, 0, 0, 0, ZoneOffset.UTC);
        String cashChangeId = actor.importHistoricalTransaction(
                cashFlowId,
                "TestCategory",
                "Test Transaction",
                "Test Description",
                Money.of(100.50, "PLN"),
                Type.OUTFLOW,
                transactionDate,
                transactionDate
        );

        // then
        CashFlowDto.CashFlowSummaryJson cashFlow = actor.getCashFlow(cashFlowId);
        assertThat(cashFlow.getCashChanges()).containsKey(cashChangeId);

        log.info("Historical transaction imported via REST API. CashChangeId: {}", cashChangeId);
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

        // then: retrieve mappings
        BankDataIngestionDto.GetMappingsResponse mappings = actor.getMappings(cashFlowId);
        assertThat(mappings.getMappings()).hasSize(2);

        log.info("Configured and retrieved {} mappings via REST API", mappings.getMappings().size());
    }

    // Note: Tests for stage/import endpoints are in BankDataIngestionControllerTest
    // which uses direct controller injection. The REST endpoints for staging and import
    // will be added in a future implementation.
}
