package com.multi.vidulum.bank_data_ingestion.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multi.vidulum.JsonFormatter;
import com.multi.vidulum.bank_data_ingestion.domain.BankDataIngestionEvent;
import com.multi.vidulum.bank_data_ingestion.domain.BankDataIngestionEventEmitter;
import com.multi.vidulum.bank_data_ingestion.domain.ImportPhase;
import com.multi.vidulum.bank_data_ingestion.domain.MappingAction;
import com.multi.vidulum.bank_data_ingestion.infrastructure.CategoryMappingMongoRepository;
import com.multi.vidulum.bank_data_ingestion.infrastructure.ImportJobMongoRepository;
import com.multi.vidulum.bank_data_ingestion.infrastructure.StagedTransactionMongoRepository;
import com.multi.vidulum.cashflow.app.CashFlowDto;
import com.multi.vidulum.cashflow.app.CashFlowRestController;
import com.multi.vidulum.cashflow.domain.BankAccount;
import com.multi.vidulum.cashflow.domain.BankAccountNumber;
import com.multi.vidulum.cashflow.domain.BankName;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.cashflow.infrastructure.CashFlowMongoRepository;
import com.multi.vidulum.cashflow_forecast_processor.infrastructure.CashFlowForecastMongoRepository;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.events.BankDataIngestionUnifiedEvent;
import com.multi.vidulum.config.FixedClockConfig;
import com.multi.vidulum.portfolio.app.PortfolioAppConfig;
import com.multi.vidulum.trading.app.TradingAppConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for BankDataIngestion module.
 *
 * Uses direct controller injection similar to DualBudgetActor pattern,
 * avoiding HTTP communication and commandGateway complexity.
 */
@Slf4j
@SpringBootTest(classes = FixedClockConfig.class)
@Import({PortfolioAppConfig.class, TradingAppConfig.class, BankDataIngestionControllerTest.TestCashFlowServiceClientConfig.class})
@Testcontainers
@DirtiesContext
public class BankDataIngestionControllerTest {

    @org.springframework.boot.test.context.TestConfiguration
    static class TestCashFlowServiceClientConfig {
        @org.springframework.context.annotation.Bean
        public CashFlowServiceClient cashFlowServiceClient(
                com.multi.vidulum.shared.cqrs.QueryGateway queryGateway,
                @org.springframework.context.annotation.Lazy com.multi.vidulum.shared.cqrs.CommandGateway commandGateway) {
            return new TestCashFlowServiceClient(queryGateway, commandGateway);
        }
    }

    @Container
    public static KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));

    @Container
    protected static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:4.4.6");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
        registry.add("mongodb.port", mongoDBContainer::getFirstMappedPort);
        registry.add("spring.kafka.bootstrap-servers", () -> kafka.getBootstrapServers());
        // Disable the HttpCashFlowServiceClient - tests use direct controller injection
        registry.add("vidulum.cashflow-service.enabled", () -> "false");
    }

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Autowired
    private CashFlowRestController cashFlowRestController;

    @Autowired
    private BankDataIngestionRestController bankDataIngestionRestController;

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

    private JsonFormatter jsonFormatter = new JsonFormatter();

    @BeforeEach
    public void beforeTest() {
        kafkaListenerEndpointRegistry.getListenerContainers().forEach(
                messageListenerContainer -> ContainerTestUtils.waitForAssignment(messageListenerContainer, 1));
        categoryMappingMongoRepository.deleteAll();
        stagedTransactionMongoRepository.deleteAll();
        importJobMongoRepository.deleteAll();
        cashFlowMongoRepository.deleteAll();
        cashFlowForecastMongoRepository.deleteAll();
    }

    @Test
    @DisplayName("Should configure category mappings for a CashFlow")
    void shouldConfigureCategoryMappings() {
        // given: create a CashFlow
        String cashFlowId = createCashFlowWithHistory();

        // when: configure mappings
        BankDataIngestionDto.ConfigureMappingsRequest request = BankDataIngestionDto.ConfigureMappingsRequest.builder()
                .mappings(List.of(
                        BankDataIngestionDto.MappingConfigJson.builder()
                                .bankCategoryName("Zakupy kartą")
                                .action(MappingAction.CREATE_NEW)
                                .targetCategoryName("Groceries")
                                .categoryType(Type.OUTFLOW)
                                .build(),
                        BankDataIngestionDto.MappingConfigJson.builder()
                                .bankCategoryName("Netflix")
                                .action(MappingAction.CREATE_SUBCATEGORY)
                                .targetCategoryName("Netflix")
                                .parentCategoryName("Subscriptions")
                                .categoryType(Type.OUTFLOW)
                                .build(),
                        BankDataIngestionDto.MappingConfigJson.builder()
                                .bankCategoryName("Przelew własny")
                                .action(MappingAction.CREATE_NEW)
                                .targetCategoryName("Transfers Out")
                                .categoryType(Type.OUTFLOW)
                                .build(),
                        BankDataIngestionDto.MappingConfigJson.builder()
                                .bankCategoryName("Przelew własny")
                                .action(MappingAction.CREATE_NEW)
                                .targetCategoryName("Salary")
                                .categoryType(Type.INFLOW)
                                .build(),
                        BankDataIngestionDto.MappingConfigJson.builder()
                                .bankCategoryName("Opłata bankowa")
                                .action(MappingAction.MAP_TO_UNCATEGORIZED)
                                .categoryType(Type.OUTFLOW)
                                .build()
                ))
                .build();

        BankDataIngestionDto.ConfigureMappingsResponse response = bankDataIngestionRestController.configureMappings(cashFlowId, request);

        log.info("Configure mappings response:\n{}", jsonFormatter.formatToPrettyJson(response));

        // then: verify response
        assertThat(response.getCashFlowId()).isEqualTo(cashFlowId);
        assertThat(response.getMappingsConfigured()).isEqualTo(5);
        assertThat(response.getMappings()).hasSize(5);

        // verify each mapping
        assertThat(response.getMappings()).extracting(BankDataIngestionDto.MappingResultJson::getBankCategoryName)
                .containsExactlyInAnyOrder("Zakupy kartą", "Netflix", "Przelew własny", "Przelew własny", "Opłata bankowa");

        assertThat(response.getMappings()).extracting(BankDataIngestionDto.MappingResultJson::getStatus)
                .containsOnly("CREATED");
    }

    @Test
    @DisplayName("Should update existing mapping when reconfiguring")
    void shouldUpdateExistingMapping() {
        // given: create a CashFlow and configure initial mapping
        String cashFlowId = createCashFlowWithHistory();

        BankDataIngestionDto.ConfigureMappingsRequest initialRequest = BankDataIngestionDto.ConfigureMappingsRequest.builder()
                .mappings(List.of(
                        BankDataIngestionDto.MappingConfigJson.builder()
                                .bankCategoryName("Zakupy kartą")
                                .action(MappingAction.CREATE_NEW)
                                .targetCategoryName("Groceries")
                                .categoryType(Type.OUTFLOW)
                                .build()
                ))
                .build();

        bankDataIngestionRestController.configureMappings(cashFlowId, initialRequest);

        // when: update the mapping with different target
        BankDataIngestionDto.ConfigureMappingsRequest updateRequest = BankDataIngestionDto.ConfigureMappingsRequest.builder()
                .mappings(List.of(
                        BankDataIngestionDto.MappingConfigJson.builder()
                                .bankCategoryName("Zakupy kartą")
                                .action(MappingAction.CREATE_NEW)
                                .targetCategoryName("Shopping")
                                .categoryType(Type.OUTFLOW)
                                .build()
                ))
                .build();

        BankDataIngestionDto.ConfigureMappingsResponse response = bankDataIngestionRestController.configureMappings(cashFlowId, updateRequest);

        log.info("Update mapping response:\n{}", jsonFormatter.formatToPrettyJson(response));

        // then: verify the mapping was updated
        assertThat(response.getMappingsConfigured()).isEqualTo(1);
        assertThat(response.getMappings().get(0).getStatus()).isEqualTo("UPDATED");
        assertThat(response.getMappings().get(0).getTargetCategoryName()).isEqualTo("Shopping");
    }

    @Test
    @DisplayName("Should get all category mappings for a CashFlow")
    void shouldGetCategoryMappings() {
        // given: create a CashFlow and configure mappings
        String cashFlowId = createCashFlowWithHistory();

        BankDataIngestionDto.ConfigureMappingsRequest request = BankDataIngestionDto.ConfigureMappingsRequest.builder()
                .mappings(List.of(
                        BankDataIngestionDto.MappingConfigJson.builder()
                                .bankCategoryName("Zakupy kartą")
                                .action(MappingAction.CREATE_NEW)
                                .targetCategoryName("Groceries")
                                .categoryType(Type.OUTFLOW)
                                .build(),
                        BankDataIngestionDto.MappingConfigJson.builder()
                                .bankCategoryName("Salary")
                                .action(MappingAction.CREATE_NEW)
                                .targetCategoryName("Income")
                                .categoryType(Type.INFLOW)
                                .build()
                ))
                .build();

        bankDataIngestionRestController.configureMappings(cashFlowId, request);

        // when: get mappings
        BankDataIngestionDto.GetMappingsResponse response = bankDataIngestionRestController.getMappings(cashFlowId);

        log.info("Get mappings response:\n{}", jsonFormatter.formatToPrettyJson(response));

        // then: verify response
        assertThat(response.getCashFlowId()).isEqualTo(cashFlowId);
        assertThat(response.getMappingsCount()).isEqualTo(2);
        assertThat(response.getMappings()).hasSize(2);
        assertThat(response.getMappings()).extracting(BankDataIngestionDto.MappingJson::getBankCategoryName)
                .containsExactlyInAnyOrder("Zakupy kartą", "Salary");
    }

    @Test
    @DisplayName("Should delete a single category mapping")
    void shouldDeleteSingleMapping() {
        // given: create a CashFlow and configure mappings
        String cashFlowId = createCashFlowWithHistory();

        BankDataIngestionDto.ConfigureMappingsRequest request = BankDataIngestionDto.ConfigureMappingsRequest.builder()
                .mappings(List.of(
                        BankDataIngestionDto.MappingConfigJson.builder()
                                .bankCategoryName("Zakupy kartą")
                                .action(MappingAction.CREATE_NEW)
                                .targetCategoryName("Groceries")
                                .categoryType(Type.OUTFLOW)
                                .build(),
                        BankDataIngestionDto.MappingConfigJson.builder()
                                .bankCategoryName("Salary")
                                .action(MappingAction.CREATE_NEW)
                                .targetCategoryName("Income")
                                .categoryType(Type.INFLOW)
                                .build()
                ))
                .build();

        BankDataIngestionDto.ConfigureMappingsResponse configureResponse = bankDataIngestionRestController.configureMappings(cashFlowId, request);
        String mappingIdToDelete = configureResponse.getMappings().get(0).getMappingId();

        // when: delete the first mapping
        BankDataIngestionDto.DeleteMappingResponse deleteResponse = bankDataIngestionRestController.deleteMapping(cashFlowId, mappingIdToDelete);

        log.info("Delete mapping response:\n{}", jsonFormatter.formatToPrettyJson(deleteResponse));

        // then: verify the mapping was deleted
        assertThat(deleteResponse.isDeleted()).isTrue();
        assertThat(deleteResponse.getMappingId()).isEqualTo(mappingIdToDelete);

        // verify only one mapping remains
        BankDataIngestionDto.GetMappingsResponse getMappingsResponse = bankDataIngestionRestController.getMappings(cashFlowId);
        assertThat(getMappingsResponse.getMappingsCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should delete all category mappings for a CashFlow")
    void shouldDeleteAllMappings() {
        // given: create a CashFlow and configure mappings
        String cashFlowId = createCashFlowWithHistory();

        BankDataIngestionDto.ConfigureMappingsRequest request = BankDataIngestionDto.ConfigureMappingsRequest.builder()
                .mappings(List.of(
                        BankDataIngestionDto.MappingConfigJson.builder()
                                .bankCategoryName("Zakupy kartą")
                                .action(MappingAction.CREATE_NEW)
                                .targetCategoryName("Groceries")
                                .categoryType(Type.OUTFLOW)
                                .build(),
                        BankDataIngestionDto.MappingConfigJson.builder()
                                .bankCategoryName("Salary")
                                .action(MappingAction.CREATE_NEW)
                                .targetCategoryName("Income")
                                .categoryType(Type.INFLOW)
                                .build()
                ))
                .build();

        bankDataIngestionRestController.configureMappings(cashFlowId, request);

        // when: delete all mappings
        BankDataIngestionDto.DeleteAllMappingsResponse deleteResponse = bankDataIngestionRestController.deleteAllMappings(cashFlowId);

        log.info("Delete all mappings response:\n{}", jsonFormatter.formatToPrettyJson(deleteResponse));

        // then: verify all mappings were deleted
        assertThat(deleteResponse.isDeleted()).isTrue();
        assertThat(deleteResponse.getDeletedCount()).isEqualTo(2);

        // verify no mappings remain
        BankDataIngestionDto.GetMappingsResponse getMappingsResponse = bankDataIngestionRestController.getMappings(cashFlowId);
        assertThat(getMappingsResponse.getMappingsCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should return empty list when no mappings configured")
    void shouldReturnEmptyListWhenNoMappings() {
        // given: create a CashFlow without any mappings
        String cashFlowId = createCashFlowWithHistory();

        // when: get mappings
        BankDataIngestionDto.GetMappingsResponse response = bankDataIngestionRestController.getMappings(cashFlowId);

        log.info("Get mappings (empty) response:\n{}", jsonFormatter.formatToPrettyJson(response));

        // then: verify empty list
        assertThat(response.getCashFlowId()).isEqualTo(cashFlowId);
        assertThat(response.getMappingsCount()).isEqualTo(0);
        assertThat(response.getMappings()).isEmpty();
    }

    @Test
    @DisplayName("Should handle same bank category with different types separately")
    void shouldHandleSameBankCategoryWithDifferentTypes() {
        // given: create a CashFlow
        String cashFlowId = createCashFlowWithHistory();

        // "Przelew własny" can be both INFLOW (receiving) and OUTFLOW (sending)
        BankDataIngestionDto.ConfigureMappingsRequest request = BankDataIngestionDto.ConfigureMappingsRequest.builder()
                .mappings(List.of(
                        BankDataIngestionDto.MappingConfigJson.builder()
                                .bankCategoryName("Przelew własny")
                                .action(MappingAction.CREATE_NEW)
                                .targetCategoryName("Transfers Out")
                                .categoryType(Type.OUTFLOW)
                                .build(),
                        BankDataIngestionDto.MappingConfigJson.builder()
                                .bankCategoryName("Przelew własny")
                                .action(MappingAction.CREATE_NEW)
                                .targetCategoryName("Transfers In")
                                .categoryType(Type.INFLOW)
                                .build()
                ))
                .build();

        // when: configure mappings
        BankDataIngestionDto.ConfigureMappingsResponse response = bankDataIngestionRestController.configureMappings(cashFlowId, request);

        log.info("Configure same bank category with different types response:\n{}",
                jsonFormatter.formatToPrettyJson(response));

        // then: both mappings should be created (unique by bankCategoryName + categoryType)
        assertThat(response.getMappingsConfigured()).isEqualTo(2);
        assertThat(response.getMappings()).allMatch(m -> m.getStatus().equals("CREATED"));

        // verify both are retrievable
        BankDataIngestionDto.GetMappingsResponse getMappingsResponse = bankDataIngestionRestController.getMappings(cashFlowId);
        assertThat(getMappingsResponse.getMappingsCount()).isEqualTo(2);

        // verify they have different category types
        assertThat(getMappingsResponse.getMappings())
                .extracting(BankDataIngestionDto.MappingJson::getCategoryType)
                .containsExactlyInAnyOrder(Type.INFLOW, Type.OUTFLOW);
    }

    // ============ Staging Tests ============

    @Test
    @DisplayName("Should stage transactions with mappings and return preview")
    void shouldStageTransactionsWithMappings() {
        // given: create a CashFlow and configure mappings
        String cashFlowId = createCashFlowWithHistory();
        configureMappingsForStaging(cashFlowId);

        // when: stage transactions
        BankDataIngestionDto.StageTransactionsRequest request = BankDataIngestionDto.StageTransactionsRequest.builder()
                .transactions(List.of(
                        BankDataIngestionDto.BankTransactionJson.builder()
                                .bankTransactionId("txn-001")
                                .name("Biedronka Zakupy")
                                .description("Zakupy spożywcze")
                                .bankCategory("Zakupy kartą")
                                .amount(150.50)
                                .currency("PLN")
                                .type(Type.OUTFLOW)
                                .paidDate(ZonedDateTime.of(2021, 8, 15, 10, 0, 0, 0, ZoneOffset.UTC))
                                .build(),
                        BankDataIngestionDto.BankTransactionJson.builder()
                                .bankTransactionId("txn-002")
                                .name("Netflix")
                                .description("Subskrypcja miesięczna")
                                .bankCategory("Subskrypcje")
                                .amount(49.99)
                                .currency("PLN")
                                .type(Type.OUTFLOW)
                                .paidDate(ZonedDateTime.of(2021, 8, 20, 10, 0, 0, 0, ZoneOffset.UTC))
                                .build(),
                        BankDataIngestionDto.BankTransactionJson.builder()
                                .bankTransactionId("txn-003")
                                .name("Przelew z firmy")
                                .description("Wynagrodzenie")
                                .bankCategory("Przelew przychodzący")
                                .amount(8500.00)
                                .currency("PLN")
                                .type(Type.INFLOW)
                                .paidDate(ZonedDateTime.of(2021, 9, 1, 10, 0, 0, 0, ZoneOffset.UTC))
                                .build()
                ))
                .build();

        BankDataIngestionDto.StageTransactionsResponse response = bankDataIngestionRestController.stageTransactions(cashFlowId, request);

        log.info("Stage transactions response - status: {}, sessionId: {}", response.getStatus(), response.getStagingSessionId());

        // then: verify staging result
        assertThat(response.getStagingSessionId()).isNotNull();
        assertThat(response.getCashFlowId()).isEqualTo(cashFlowId);
        assertThat(response.getStatus()).isEqualTo("READY_FOR_IMPORT");
        assertThat(response.getExpiresAt()).isNotNull();

        // verify summary
        assertThat(response.getSummary().getTotalTransactions()).isEqualTo(3);
        assertThat(response.getSummary().getValidTransactions()).isEqualTo(3);
        assertThat(response.getSummary().getInvalidTransactions()).isEqualTo(0);
        assertThat(response.getSummary().getDuplicateTransactions()).isEqualTo(0);

        // verify category breakdown
        assertThat(response.getCategoryBreakdown()).isNotEmpty();

        // verify monthly breakdown
        assertThat(response.getMonthlyBreakdown()).hasSize(2); // August and September
    }

    @Test
    @DisplayName("Should return HAS_UNMAPPED_CATEGORIES when mappings are missing")
    void shouldReturnUnmappedCategoriesWhenMappingsMissing() {
        // given: create a CashFlow WITHOUT configuring mappings
        String cashFlowId = createCashFlowWithHistory();

        // when: stage transactions with unmapped categories
        BankDataIngestionDto.StageTransactionsRequest request = BankDataIngestionDto.StageTransactionsRequest.builder()
                .transactions(List.of(
                        BankDataIngestionDto.BankTransactionJson.builder()
                                .bankTransactionId("txn-001")
                                .name("Unknown Transaction")
                                .description("Test")
                                .bankCategory("Unknown Category")
                                .amount(100.0)
                                .currency("PLN")
                                .type(Type.OUTFLOW)
                                .paidDate(ZonedDateTime.of(2021, 8, 15, 10, 0, 0, 0, ZoneOffset.UTC))
                                .build()
                ))
                .build();

        BankDataIngestionDto.StageTransactionsResponse response = bankDataIngestionRestController.stageTransactions(cashFlowId, request);

        log.info("Stage transactions with unmapped categories response - status: {}", response.getStatus());

        // then: verify unmapped categories result
        assertThat(response.getStatus()).isEqualTo("HAS_UNMAPPED_CATEGORIES");
        assertThat(response.getUnmappedCategories()).hasSize(1);
        assertThat(response.getUnmappedCategories().get(0).getBankCategory()).isEqualTo("Unknown Category");
        assertThat(response.getUnmappedCategories().get(0).getType()).isEqualTo(Type.OUTFLOW);
        assertThat(response.getUnmappedCategories().get(0).getCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should return HAS_VALIDATION_ERRORS for transactions outside SETUP period")
    void shouldReturnValidationErrorsForInvalidPeriod() {
        // given: create a CashFlow and configure mappings
        String cashFlowId = createCashFlowWithHistory();
        configureMappingsForStaging(cashFlowId);

        // when: stage transactions with paidDate in the future (after activePeriod)
        // Fixed clock is at 2022-01-01, activePeriod is 2022-01
        // startPeriod is 2021-07, so valid dates are 2021-07 to 2021-12
        BankDataIngestionDto.StageTransactionsRequest request = BankDataIngestionDto.StageTransactionsRequest.builder()
                .transactions(List.of(
                        BankDataIngestionDto.BankTransactionJson.builder()
                                .bankTransactionId("txn-001")
                                .name("Future Transaction")
                                .description("Test")
                                .bankCategory("Zakupy kartą")
                                .amount(100.0)
                                .currency("PLN")
                                .type(Type.OUTFLOW)
                                .paidDate(ZonedDateTime.of(2022, 1, 15, 10, 0, 0, 0, ZoneOffset.UTC)) // activePeriod!
                                .build()
                ))
                .build();

        BankDataIngestionDto.StageTransactionsResponse response = bankDataIngestionRestController.stageTransactions(cashFlowId, request);

        log.info("Stage transactions with invalid period response - status: {}, invalid: {}",
                response.getStatus(), response.getSummary().getInvalidTransactions());

        // then: verify validation errors
        assertThat(response.getStatus()).isEqualTo("HAS_VALIDATION_ERRORS");
        assertThat(response.getSummary().getInvalidTransactions()).isEqualTo(1);
        assertThat(response.getSummary().getValidTransactions()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should get staging preview for existing session")
    void shouldGetStagingPreview() {
        // given: create a CashFlow, configure mappings, and stage transactions
        String cashFlowId = createCashFlowWithHistory();
        configureMappingsForStaging(cashFlowId);

        BankDataIngestionDto.StageTransactionsRequest stageRequest = BankDataIngestionDto.StageTransactionsRequest.builder()
                .transactions(List.of(
                        BankDataIngestionDto.BankTransactionJson.builder()
                                .bankTransactionId("txn-001")
                                .name("Biedronka Zakupy")
                                .description("Zakupy spożywcze")
                                .bankCategory("Zakupy kartą")
                                .amount(150.50)
                                .currency("PLN")
                                .type(Type.OUTFLOW)
                                .paidDate(ZonedDateTime.of(2021, 8, 15, 10, 0, 0, 0, ZoneOffset.UTC))
                                .build()
                ))
                .build();

        BankDataIngestionDto.StageTransactionsResponse stageResponse = bankDataIngestionRestController.stageTransactions(cashFlowId, stageRequest);
        String stagingSessionId = stageResponse.getStagingSessionId();

        // when: get staging preview
        BankDataIngestionDto.GetStagingPreviewResponse response = bankDataIngestionRestController.getStagingPreview(cashFlowId, stagingSessionId);

        log.info("Get staging preview response - status: {}, transactions: {}", response.getStatus(), response.getTransactions().size());

        // then: verify preview content
        assertThat(response.getStagingSessionId()).isEqualTo(stagingSessionId);
        assertThat(response.getCashFlowId()).isEqualTo(cashFlowId);
        assertThat(response.getStatus()).isEqualTo("READY_FOR_IMPORT");

        // verify transactions are included
        assertThat(response.getTransactions()).hasSize(1);
        assertThat(response.getTransactions().get(0).getBankTransactionId()).isEqualTo("txn-001");
        assertThat(response.getTransactions().get(0).getName()).isEqualTo("Biedronka Zakupy");
        assertThat(response.getTransactions().get(0).getTargetCategory()).isEqualTo("Groceries");
        assertThat(response.getTransactions().get(0).getValidation().getStatus()).isEqualTo("VALID");
    }

    @Test
    @DisplayName("Should return NOT_FOUND for non-existent staging session")
    void shouldReturnNotFoundForNonExistentSession() {
        // given: create a CashFlow
        String cashFlowId = createCashFlowWithHistory();

        // when: get staging preview for non-existent session
        BankDataIngestionDto.GetStagingPreviewResponse response = bankDataIngestionRestController.getStagingPreview(cashFlowId, "non-existent-session-id");

        log.info("Get staging preview (not found) response - status: {}", response.getStatus());

        // then: verify NOT_FOUND status
        assertThat(response.getStatus()).isEqualTo("NOT_FOUND");
        assertThat(response.getSummary().getTotalTransactions()).isEqualTo(0);
        assertThat(response.getTransactions()).isEmpty();
    }

    @Test
    @DisplayName("Should delete staging session and all its transactions")
    void shouldDeleteStagingSession() {
        // given: create a CashFlow, configure mappings, and stage transactions
        String cashFlowId = createCashFlowWithHistory();
        configureMappingsForStaging(cashFlowId);

        BankDataIngestionDto.StageTransactionsRequest stageRequest = BankDataIngestionDto.StageTransactionsRequest.builder()
                .transactions(List.of(
                        BankDataIngestionDto.BankTransactionJson.builder()
                                .bankTransactionId("txn-001")
                                .name("Biedronka Zakupy")
                                .description("Zakupy spożywcze")
                                .bankCategory("Zakupy kartą")
                                .amount(150.50)
                                .currency("PLN")
                                .type(Type.OUTFLOW)
                                .paidDate(ZonedDateTime.of(2021, 8, 15, 10, 0, 0, 0, ZoneOffset.UTC))
                                .build(),
                        BankDataIngestionDto.BankTransactionJson.builder()
                                .bankTransactionId("txn-002")
                                .name("Netflix")
                                .description("Subskrypcja")
                                .bankCategory("Subskrypcje")
                                .amount(49.99)
                                .currency("PLN")
                                .type(Type.OUTFLOW)
                                .paidDate(ZonedDateTime.of(2021, 8, 20, 10, 0, 0, 0, ZoneOffset.UTC))
                                .build()
                ))
                .build();

        BankDataIngestionDto.StageTransactionsResponse stageResponse = bankDataIngestionRestController.stageTransactions(cashFlowId, stageRequest);
        String stagingSessionId = stageResponse.getStagingSessionId();

        // when: delete staging session
        BankDataIngestionDto.DeleteStagingSessionResponse response = bankDataIngestionRestController.deleteStagingSession(cashFlowId, stagingSessionId);

        log.info("Delete staging session response - deleted: {}, count: {}", response.isDeleted(), response.getDeletedCount());

        // then: verify deletion
        assertThat(response.isDeleted()).isTrue();
        assertThat(response.getDeletedCount()).isEqualTo(2);
        assertThat(response.getStagingSessionId()).isEqualTo(stagingSessionId);

        // verify session is gone
        BankDataIngestionDto.GetStagingPreviewResponse previewResponse = bankDataIngestionRestController.getStagingPreview(cashFlowId, stagingSessionId);
        assertThat(previewResponse.getStatus()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("Should handle delete of non-existent staging session gracefully")
    void shouldHandleDeleteOfNonExistentSession() {
        // given: create a CashFlow
        String cashFlowId = createCashFlowWithHistory();

        // when: delete non-existent staging session
        BankDataIngestionDto.DeleteStagingSessionResponse response = bankDataIngestionRestController.deleteStagingSession(cashFlowId, "non-existent-session-id");

        log.info("Delete non-existent staging session response - deleted: {}, count: {}", response.isDeleted(), response.getDeletedCount());

        // then: verify no error, just not deleted
        assertThat(response.isDeleted()).isFalse();
        assertThat(response.getDeletedCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should list active staging sessions for a CashFlow")
    void shouldListActiveStagingSessions() {
        // given: create a CashFlow and configure mappings
        String cashFlowId = createCashFlowWithHistory();
        configureMappingsForStaging(cashFlowId);

        // Stage first batch of transactions
        BankDataIngestionDto.StageTransactionsRequest stageRequest1 = BankDataIngestionDto.StageTransactionsRequest.builder()
                .transactions(List.of(
                        BankDataIngestionDto.BankTransactionJson.builder()
                                .bankTransactionId("txn-001")
                                .name("Biedronka Zakupy")
                                .description("Zakupy spożywcze")
                                .bankCategory("Zakupy kartą")
                                .amount(150.50)
                                .currency("PLN")
                                .type(Type.OUTFLOW)
                                .paidDate(ZonedDateTime.of(2021, 8, 15, 10, 0, 0, 0, ZoneOffset.UTC))
                                .build(),
                        BankDataIngestionDto.BankTransactionJson.builder()
                                .bankTransactionId("txn-002")
                                .name("Netflix")
                                .description("Subskrypcja")
                                .bankCategory("Subskrypcje")
                                .amount(49.99)
                                .currency("PLN")
                                .type(Type.OUTFLOW)
                                .paidDate(ZonedDateTime.of(2021, 8, 20, 10, 0, 0, 0, ZoneOffset.UTC))
                                .build()
                ))
                .build();

        BankDataIngestionDto.StageTransactionsResponse stageResponse1 = bankDataIngestionRestController.stageTransactions(cashFlowId, stageRequest1);
        String stagingSessionId = stageResponse1.getStagingSessionId();

        // when: list staging sessions
        BankDataIngestionDto.ListStagingSessionsResponse response = bankDataIngestionRestController.listStagingSessions(cashFlowId);

        log.info("List staging sessions response - hasPendingImport: {}, sessions count: {}",
                response.isHasPendingImport(), response.getStagingSessions().size());

        // then: verify we have one active staging session
        assertThat(response.getCashFlowId()).isEqualTo(cashFlowId);
        assertThat(response.isHasPendingImport()).isTrue();
        assertThat(response.getStagingSessions()).hasSize(1);

        BankDataIngestionDto.StagingSessionSummaryJson sessionSummary = response.getStagingSessions().get(0);
        assertThat(sessionSummary.getStagingSessionId()).isEqualTo(stagingSessionId);
        assertThat(sessionSummary.getStatus()).isEqualTo("READY_FOR_IMPORT");
        assertThat(sessionSummary.getCounts().getTotalTransactions()).isEqualTo(2);
        assertThat(sessionSummary.getCounts().getValidTransactions()).isEqualTo(2);
        assertThat(sessionSummary.getCounts().getInvalidTransactions()).isEqualTo(0);
        assertThat(sessionSummary.getCounts().getDuplicateTransactions()).isEqualTo(0);
        assertThat(sessionSummary.getExpiresAt()).isAfter(sessionSummary.getCreatedAt());
    }

    @Test
    @DisplayName("Should return empty list when no active staging sessions exist")
    void shouldReturnEmptyListWhenNoActiveStagingSessions() {
        // given: create a CashFlow with no staging sessions
        String cashFlowId = createCashFlowWithHistory();

        // when: list staging sessions
        BankDataIngestionDto.ListStagingSessionsResponse response = bankDataIngestionRestController.listStagingSessions(cashFlowId);

        log.info("List staging sessions (empty) response - hasPendingImport: {}, sessions count: {}",
                response.isHasPendingImport(), response.getStagingSessions().size());

        // then: verify empty response
        assertThat(response.getCashFlowId()).isEqualTo(cashFlowId);
        assertThat(response.isHasPendingImport()).isFalse();
        assertThat(response.getStagingSessions()).isEmpty();
    }

    // ============ Import Job Tests ============

    @Test
    @DisplayName("Should start import job and complete successfully")
    void shouldStartImportJobAndComplete() {
        // given: create a CashFlow, configure mappings, and stage transactions
        String cashFlowId = createCashFlowWithHistory();
        configureMappingsForStaging(cashFlowId);

        BankDataIngestionDto.StageTransactionsRequest stageRequest = BankDataIngestionDto.StageTransactionsRequest.builder()
                .transactions(List.of(
                        BankDataIngestionDto.BankTransactionJson.builder()
                                .bankTransactionId("txn-001")
                                .name("Biedronka Zakupy")
                                .description("Zakupy spożywcze")
                                .bankCategory("Zakupy kartą")
                                .amount(150.50)
                                .currency("PLN")
                                .type(Type.OUTFLOW)
                                .paidDate(ZonedDateTime.of(2021, 8, 15, 10, 0, 0, 0, ZoneOffset.UTC))
                                .build(),
                        BankDataIngestionDto.BankTransactionJson.builder()
                                .bankTransactionId("txn-002")
                                .name("Przelew z firmy")
                                .description("Wynagrodzenie")
                                .bankCategory("Przelew przychodzący")
                                .amount(8500.00)
                                .currency("PLN")
                                .type(Type.INFLOW)
                                .paidDate(ZonedDateTime.of(2021, 9, 1, 10, 0, 0, 0, ZoneOffset.UTC))
                                .build()
                ))
                .build();

        BankDataIngestionDto.StageTransactionsResponse stageResponse = bankDataIngestionRestController.stageTransactions(cashFlowId, stageRequest);
        String stagingSessionId = stageResponse.getStagingSessionId();

        // when: start import
        BankDataIngestionDto.StartImportRequest importRequest = BankDataIngestionDto.StartImportRequest.builder()
                .stagingSessionId(stagingSessionId)
                .build();

        BankDataIngestionDto.StartImportResponse importResponse = bankDataIngestionRestController.startImport(cashFlowId, importRequest);

        log.info("Start import response - status: {}, jobId: {}", importResponse.getStatus(), importResponse.getJobId());

        // then: verify import completed
        assertThat(importResponse.getJobId()).isNotNull();
        assertThat(importResponse.getCashFlowId()).isEqualTo(cashFlowId);
        assertThat(importResponse.getStatus()).isEqualTo("COMPLETED");
        assertThat(importResponse.getResult().getTransactionsImported()).isEqualTo(2);
        assertThat(importResponse.isCanRollback()).isTrue();
        assertThat(importResponse.getPollUrl()).contains(importResponse.getJobId());
    }

    @Test
    @DisplayName("Should get import job progress")
    void shouldGetImportJobProgress() {
        // given: create and complete an import job
        String cashFlowId = createCashFlowWithHistory();
        configureMappingsForStaging(cashFlowId);

        String stagingSessionId = stageTransactionsForImport(cashFlowId);

        BankDataIngestionDto.StartImportRequest importRequest = BankDataIngestionDto.StartImportRequest.builder()
                .stagingSessionId(stagingSessionId)
                .build();

        BankDataIngestionDto.StartImportResponse importResponse = bankDataIngestionRestController.startImport(cashFlowId, importRequest);
        String jobId = importResponse.getJobId();

        // when: get import progress
        BankDataIngestionDto.GetImportProgressResponse progressResponse = bankDataIngestionRestController.getImportProgress(cashFlowId, jobId);

        log.info("Import progress - status: {}, percentage: {}%",
                progressResponse.getStatus(), progressResponse.getProgress().getPercentage());

        // then: verify progress
        assertThat(progressResponse.getJobId()).isEqualTo(jobId);
        assertThat(progressResponse.getStatus()).isEqualTo("COMPLETED");
        assertThat(progressResponse.getProgress().getPercentage()).isEqualTo(100);
        assertThat(progressResponse.getSummary()).isNotNull();
        assertThat(progressResponse.isCanRollback()).isTrue();
    }

    @Test
    @DisplayName("Should finalize import job")
    void shouldFinalizeImportJob() {
        // given: create and complete an import job
        String cashFlowId = createCashFlowWithHistory();
        configureMappingsForStaging(cashFlowId);

        String stagingSessionId = stageTransactionsForImport(cashFlowId);

        BankDataIngestionDto.StartImportRequest importRequest = BankDataIngestionDto.StartImportRequest.builder()
                .stagingSessionId(stagingSessionId)
                .build();

        BankDataIngestionDto.StartImportResponse importResponse = bankDataIngestionRestController.startImport(cashFlowId, importRequest);
        String jobId = importResponse.getJobId();

        // when: finalize import
        BankDataIngestionDto.FinalizeImportRequest finalizeRequest = BankDataIngestionDto.FinalizeImportRequest.builder()
                .deleteMappings(false)
                .build();

        BankDataIngestionDto.FinalizeImportResponse finalizeResponse = bankDataIngestionRestController.finalizeImport(cashFlowId, jobId, finalizeRequest);

        log.info("Finalize import response - status: {}, stagedDeleted: {}",
                finalizeResponse.getStatus(), finalizeResponse.getCleanup().getStagedTransactionsDeleted());

        // then: verify finalization
        assertThat(finalizeResponse.getJobId()).isEqualTo(jobId);
        assertThat(finalizeResponse.getStatus()).isEqualTo("FINALIZED");
        assertThat(finalizeResponse.getCleanup().getStagedTransactionsDeleted()).isGreaterThan(0);
        assertThat(finalizeResponse.getCleanup().getMappingsDeleted()).isEqualTo(0);
        assertThat(finalizeResponse.getFinalSummary().getTransactionsImported()).isGreaterThan(0);

        // verify staging session is deleted
        BankDataIngestionDto.GetStagingPreviewResponse previewResponse = bankDataIngestionRestController.getStagingPreview(cashFlowId, stagingSessionId);
        assertThat(previewResponse.getStatus()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("Should finalize import job and delete mappings")
    void shouldFinalizeImportJobAndDeleteMappings() {
        // given: create and complete an import job
        String cashFlowId = createCashFlowWithHistory();
        configureMappingsForStaging(cashFlowId);

        String stagingSessionId = stageTransactionsForImport(cashFlowId);

        BankDataIngestionDto.StartImportRequest importRequest = BankDataIngestionDto.StartImportRequest.builder()
                .stagingSessionId(stagingSessionId)
                .build();

        BankDataIngestionDto.StartImportResponse importResponse = bankDataIngestionRestController.startImport(cashFlowId, importRequest);
        String jobId = importResponse.getJobId();

        // when: finalize import with deleteMappings=true
        BankDataIngestionDto.FinalizeImportRequest finalizeRequest = BankDataIngestionDto.FinalizeImportRequest.builder()
                .deleteMappings(true)
                .build();

        BankDataIngestionDto.FinalizeImportResponse finalizeResponse = bankDataIngestionRestController.finalizeImport(cashFlowId, jobId, finalizeRequest);

        log.info("Finalize import response - mappingsDeleted: {}", finalizeResponse.getCleanup().getMappingsDeleted());

        // then: verify mappings deleted
        assertThat(finalizeResponse.getCleanup().getMappingsDeleted()).isGreaterThan(0);

        // verify mappings are gone
        BankDataIngestionDto.GetMappingsResponse mappingsResponse = bankDataIngestionRestController.getMappings(cashFlowId);
        assertThat(mappingsResponse.getMappingsCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should rollback import job")
    void shouldRollbackImportJob() {
        // given: create and complete an import job
        String cashFlowId = createCashFlowWithHistory();
        configureMappingsForStaging(cashFlowId);

        String stagingSessionId = stageTransactionsForImport(cashFlowId);

        BankDataIngestionDto.StartImportRequest importRequest = BankDataIngestionDto.StartImportRequest.builder()
                .stagingSessionId(stagingSessionId)
                .build();

        BankDataIngestionDto.StartImportResponse importResponse = bankDataIngestionRestController.startImport(cashFlowId, importRequest);
        String jobId = importResponse.getJobId();
        int transactionsImported = importResponse.getResult().getTransactionsImported();

        // when: rollback import
        BankDataIngestionDto.RollbackImportResponse rollbackResponse = bankDataIngestionRestController.rollbackImport(cashFlowId, jobId);

        log.info("Rollback import response - status: {}, transactionsDeleted: {}",
                rollbackResponse.getStatus(), rollbackResponse.getRollbackSummary().getTransactionsDeleted());

        // then: verify rollback
        assertThat(rollbackResponse.getJobId()).isEqualTo(jobId);
        assertThat(rollbackResponse.getStatus()).isEqualTo("ROLLED_BACK");
        assertThat(rollbackResponse.getRollbackSummary().getTransactionsDeleted()).isEqualTo(transactionsImported);

        // verify job status is ROLLED_BACK
        BankDataIngestionDto.GetImportProgressResponse progressResponse = bankDataIngestionRestController.getImportProgress(cashFlowId, jobId);
        assertThat(progressResponse.getStatus()).isEqualTo("ROLLED_BACK");
        assertThat(progressResponse.isCanRollback()).isFalse();
    }

    @Test
    @DisplayName("Should list import jobs")
    void shouldListImportJobs() {
        // given: create multiple import jobs
        String cashFlowId = createCashFlowWithHistory();
        configureMappingsForStaging(cashFlowId);

        // First import
        String stagingSessionId1 = stageTransactionsForImport(cashFlowId);
        BankDataIngestionDto.StartImportResponse import1Response = bankDataIngestionRestController.startImport(
                cashFlowId,
                BankDataIngestionDto.StartImportRequest.builder().stagingSessionId(stagingSessionId1).build()
        );

        // Finalize first import
        bankDataIngestionRestController.finalizeImport(
                cashFlowId,
                import1Response.getJobId(),
                BankDataIngestionDto.FinalizeImportRequest.builder().deleteMappings(false).build()
        );

        // Second import
        String stagingSessionId2 = stageTransactionsForImport(cashFlowId);
        bankDataIngestionRestController.startImport(
                cashFlowId,
                BankDataIngestionDto.StartImportRequest.builder().stagingSessionId(stagingSessionId2).build()
        );

        // when: list import jobs
        BankDataIngestionDto.ListImportJobsResponse listResponse = bankDataIngestionRestController.listImportJobs(cashFlowId, null);

        log.info("List import jobs response - count: {}", listResponse.getJobs().size());

        // then: verify list
        assertThat(listResponse.getCashFlowId()).isEqualTo(cashFlowId);
        assertThat(listResponse.getJobs()).hasSize(2);
        assertThat(listResponse.getJobs()).extracting(BankDataIngestionDto.ImportJobSummaryJson::getStatus)
                .containsExactlyInAnyOrder("FINALIZED", "COMPLETED");
    }

    @Test
    @DisplayName("Should filter import jobs by status")
    void shouldFilterImportJobsByStatus() {
        // given: create import jobs with different statuses
        String cashFlowId = createCashFlowWithHistory();
        configureMappingsForStaging(cashFlowId);

        // First import - finalized
        String stagingSessionId1 = stageTransactionsForImport(cashFlowId);
        BankDataIngestionDto.StartImportResponse import1Response = bankDataIngestionRestController.startImport(
                cashFlowId,
                BankDataIngestionDto.StartImportRequest.builder().stagingSessionId(stagingSessionId1).build()
        );
        bankDataIngestionRestController.finalizeImport(
                cashFlowId,
                import1Response.getJobId(),
                BankDataIngestionDto.FinalizeImportRequest.builder().deleteMappings(false).build()
        );

        // Second import - completed but not finalized
        String stagingSessionId2 = stageTransactionsForImport(cashFlowId);
        bankDataIngestionRestController.startImport(
                cashFlowId,
                BankDataIngestionDto.StartImportRequest.builder().stagingSessionId(stagingSessionId2).build()
        );

        // when: filter by COMPLETED status
        BankDataIngestionDto.ListImportJobsResponse filteredResponse = bankDataIngestionRestController.listImportJobs(cashFlowId, List.of("COMPLETED"));

        log.info("Filtered import jobs response - count: {}", filteredResponse.getJobs().size());

        // then: only completed jobs
        assertThat(filteredResponse.getJobs()).hasSize(1);
        assertThat(filteredResponse.getJobs().get(0).getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("Should create categories during import when configured with CREATE_NEW")
    void shouldCreateCategoriesDuringImport() {
        // given: create a CashFlow with mapping that creates a new category
        String cashFlowId = createCashFlowWithHistory();

        // Configure mapping with CREATE_NEW action
        BankDataIngestionDto.ConfigureMappingsRequest mappingRequest = BankDataIngestionDto.ConfigureMappingsRequest.builder()
                .mappings(List.of(
                        BankDataIngestionDto.MappingConfigJson.builder()
                                .bankCategoryName("Streaming")
                                .action(MappingAction.CREATE_NEW)
                                .targetCategoryName("StreamingServices")
                                .categoryType(Type.OUTFLOW)
                                .build()
                ))
                .build();
        bankDataIngestionRestController.configureMappings(cashFlowId, mappingRequest);

        // Stage transactions including one that needs new category
        BankDataIngestionDto.StageTransactionsRequest stageRequest = BankDataIngestionDto.StageTransactionsRequest.builder()
                .transactions(List.of(
                        BankDataIngestionDto.BankTransactionJson.builder()
                                .bankTransactionId("txn-streaming-001")
                                .name("Netflix Subscription")
                                .description("Monthly subscription")
                                .bankCategory("Streaming")
                                .amount(49.99)
                                .currency("PLN")
                                .type(Type.OUTFLOW)
                                .paidDate(ZonedDateTime.of(2021, 8, 15, 10, 0, 0, 0, ZoneOffset.UTC))
                                .build()
                ))
                .build();

        BankDataIngestionDto.StageTransactionsResponse stageResponse = bankDataIngestionRestController.stageTransactions(cashFlowId, stageRequest);

        // when: start import
        BankDataIngestionDto.StartImportResponse importResponse = bankDataIngestionRestController.startImport(
                cashFlowId,
                BankDataIngestionDto.StartImportRequest.builder()
                        .stagingSessionId(stageResponse.getStagingSessionId())
                        .build()
        );

        log.info("Import with categories - status: {}, categoriesCreated: {}",
                importResponse.getStatus(), importResponse.getResult().getCategoriesCreated());

        // then: verify category was created
        assertThat(importResponse.getStatus()).isEqualTo("COMPLETED");
        assertThat(importResponse.getResult().getCategoriesCreated()).contains("StreamingServices");
        assertThat(importResponse.getResult().getTransactionsImported()).isEqualTo(1);
    }

    // ============ Kafka Event Tests ============

    @Test
    @DisplayName("Should emit Kafka events during import lifecycle: started, progress, completed")
    void shouldEmitKafkaEventsDuringImportLifecycle() {
        // given: create Kafka consumer for bank_data_ingestion topic
        Consumer<String, BankDataIngestionUnifiedEvent> consumer = createKafkaConsumer();
        consumer.subscribe(List.of(BankDataIngestionEventEmitter.TOPIC));

        // create a CashFlow, configure mappings, and stage transactions
        String cashFlowId = createCashFlowWithHistory();
        configureMappingsForStaging(cashFlowId);

        String stagingSessionId = stageTransactionsForImport(cashFlowId);

        // when: start import
        BankDataIngestionDto.StartImportRequest importRequest = BankDataIngestionDto.StartImportRequest.builder()
                .stagingSessionId(stagingSessionId)
                .build();

        BankDataIngestionDto.StartImportResponse importResponse = bankDataIngestionRestController.startImport(cashFlowId, importRequest);
        String jobId = importResponse.getJobId();

        assertThat(importResponse.getStatus()).isEqualTo("COMPLETED");

        // then: verify Kafka events were emitted
        List<BankDataIngestionUnifiedEvent> allEvents = consumeAllEvents(consumer, Duration.ofSeconds(5));
        consumer.close();

        // Filter events for THIS job only (other tests may have emitted events)
        List<BankDataIngestionUnifiedEvent> events = allEvents.stream()
                .filter(e -> jobId.equals(e.getMetadata().get("jobId")))
                .toList();

        log.info("Received {} Kafka events for job {}", events.size(), jobId);

        // Verify ImportJobStartedEvent - full object assertion
        // Note: categoriesToCreate=1 because we use CREATE_NEW mapping (not MAP_TO_EXISTING)
        BankDataIngestionEvent.ImportJobStartedEvent startedEvent = extractEvent(events, "ImportJobStartedEvent", BankDataIngestionEvent.ImportJobStartedEvent.class);
        assertThat(startedEvent).usingRecursiveComparison()
                .isEqualTo(new BankDataIngestionEvent.ImportJobStartedEvent(
                        jobId,
                        cashFlowId,
                        stagingSessionId,
                        1,  // totalTransactions
                        1,  // validTransactions
                        1,  // categoriesToCreate - using CREATE_NEW
                        ZonedDateTime.parse("2022-01-01T00:00:00Z[UTC]")
                ));

        // Verify ImportProgressEvents - with CREATE_NEW we get two progress events
        // 1. CREATING_CATEGORIES (for the category creation phase)
        // 2. IMPORTING_TRANSACTIONS (for the transaction import phase)
        List<BankDataIngestionEvent.ImportProgressEvent> progressEvents = extractAllEvents(events, "ImportProgressEvent", BankDataIngestionEvent.ImportProgressEvent.class);
        assertThat(progressEvents).hasSize(2);

        // First: CREATING_CATEGORIES phase
        assertThat(progressEvents.get(0)).usingRecursiveComparison()
                .isEqualTo(new BankDataIngestionEvent.ImportProgressEvent(
                        jobId,
                        cashFlowId,
                        ImportPhase.CREATING_CATEGORIES,
                        1,    // processed
                        1,    // total
                        100,  // percent
                        ZonedDateTime.parse("2022-01-01T00:00:00Z[UTC]")
                ));

        // Second: IMPORTING_TRANSACTIONS phase
        assertThat(progressEvents.get(1)).usingRecursiveComparison()
                .isEqualTo(new BankDataIngestionEvent.ImportProgressEvent(
                        jobId,
                        cashFlowId,
                        ImportPhase.IMPORTING_TRANSACTIONS,
                        1,    // processed
                        1,    // total
                        100,  // percent
                        ZonedDateTime.parse("2022-01-01T00:00:00Z[UTC]")
                ));

        // Verify ImportJobCompletedEvent - full object assertion
        // Note: categoriesCreated=1 because we use CREATE_NEW mapping (not MAP_TO_EXISTING)
        BankDataIngestionEvent.ImportJobCompletedEvent completedEvent = extractEvent(events, "ImportJobCompletedEvent", BankDataIngestionEvent.ImportJobCompletedEvent.class);
        assertThat(completedEvent).usingRecursiveComparison()
                .ignoringFields("durationMs")  // duration is non-deterministic
                .isEqualTo(new BankDataIngestionEvent.ImportJobCompletedEvent(
                        jobId,
                        cashFlowId,
                        1,  // categoriesCreated - using CREATE_NEW
                        1,  // transactionsImported
                        0,  // transactionsFailed
                        0L, // durationMs - ignored
                        ZonedDateTime.parse("2022-01-01T00:00:00Z[UTC]")
                ));
    }

    @Test
    @DisplayName("Should emit ImportJobRolledBackEvent when rollback is performed")
    void shouldEmitRollbackEvent() {
        // given: create Kafka consumer for bank_data_ingestion topic
        Consumer<String, BankDataIngestionUnifiedEvent> consumer = createKafkaConsumer();
        consumer.subscribe(List.of(BankDataIngestionEventEmitter.TOPIC));

        // create and complete an import job
        String cashFlowId = createCashFlowWithHistory();
        configureMappingsForStaging(cashFlowId);

        String stagingSessionId = stageTransactionsForImport(cashFlowId);

        BankDataIngestionDto.StartImportResponse importResponse = bankDataIngestionRestController.startImport(
                cashFlowId,
                BankDataIngestionDto.StartImportRequest.builder().stagingSessionId(stagingSessionId).build()
        );
        String jobId = importResponse.getJobId();

        // when: rollback import
        BankDataIngestionDto.RollbackImportResponse rollbackResponse = bankDataIngestionRestController.rollbackImport(cashFlowId, jobId);

        assertThat(rollbackResponse.getStatus()).isEqualTo("ROLLED_BACK");

        // then: verify rollback event was emitted
        List<BankDataIngestionUnifiedEvent> allEvents = consumeAllEvents(consumer, Duration.ofSeconds(5));
        consumer.close();

        // Filter events for THIS job only
        List<BankDataIngestionUnifiedEvent> events = allEvents.stream()
                .filter(e -> jobId.equals(e.getMetadata().get("jobId")))
                .toList();

        log.info("Received {} Kafka events for rollback test (job {})", events.size(), jobId);

        // Verify ImportJobRolledBackEvent - full object assertion
        BankDataIngestionEvent.ImportJobRolledBackEvent rollbackEvent = extractEvent(events, "ImportJobRolledBackEvent", BankDataIngestionEvent.ImportJobRolledBackEvent.class);
        assertThat(rollbackEvent).usingRecursiveComparison()
                .ignoringFields("rollbackDurationMs")  // duration is non-deterministic
                .isEqualTo(new BankDataIngestionEvent.ImportJobRolledBackEvent(
                        jobId,
                        cashFlowId,
                        1,  // transactionsDeleted
                        0,  // categoriesDeleted
                        0L, // rollbackDurationMs - ignored
                        ZonedDateTime.parse("2022-01-01T00:00:00Z[UTC]")
                ));
    }

    @Test
    @DisplayName("Should emit ImportJobFinalizedEvent when finalize is performed")
    void shouldEmitFinalizeEvent() {
        // given: create Kafka consumer for bank_data_ingestion topic
        Consumer<String, BankDataIngestionUnifiedEvent> consumer = createKafkaConsumer();
        consumer.subscribe(List.of(BankDataIngestionEventEmitter.TOPIC));

        // create and complete an import job
        String cashFlowId = createCashFlowWithHistory();
        configureMappingsForStaging(cashFlowId);

        String stagingSessionId = stageTransactionsForImport(cashFlowId);

        BankDataIngestionDto.StartImportResponse importResponse = bankDataIngestionRestController.startImport(
                cashFlowId,
                BankDataIngestionDto.StartImportRequest.builder().stagingSessionId(stagingSessionId).build()
        );
        String jobId = importResponse.getJobId();

        // when: finalize import
        BankDataIngestionDto.FinalizeImportResponse finalizeResponse = bankDataIngestionRestController.finalizeImport(
                cashFlowId,
                jobId,
                BankDataIngestionDto.FinalizeImportRequest.builder().deleteMappings(false).build()
        );

        assertThat(finalizeResponse.getStatus()).isEqualTo("FINALIZED");

        // then: verify finalize event was emitted
        List<BankDataIngestionUnifiedEvent> allEvents = consumeAllEvents(consumer, Duration.ofSeconds(5));
        consumer.close();

        // Filter events for THIS job only
        List<BankDataIngestionUnifiedEvent> events = allEvents.stream()
                .filter(e -> jobId.equals(e.getMetadata().get("jobId")))
                .toList();

        log.info("Received {} Kafka events for finalize test (job {})", events.size(), jobId);

        // Verify ImportJobFinalizedEvent - full object assertion
        BankDataIngestionEvent.ImportJobFinalizedEvent finalizedEvent = extractEvent(events, "ImportJobFinalizedEvent", BankDataIngestionEvent.ImportJobFinalizedEvent.class);
        assertThat(finalizedEvent).usingRecursiveComparison()
                .isEqualTo(new BankDataIngestionEvent.ImportJobFinalizedEvent(
                        jobId,
                        cashFlowId,
                        1,  // stagedTransactionsDeleted
                        0,  // mappingsDeleted (deleteMappings=false)
                        ZonedDateTime.parse("2022-01-01T00:00:00Z[UTC]")
                ));
    }

    // ============ Helper Methods ============

    private Consumer<String, BankDataIngestionUnifiedEvent> createKafkaConsumer() {
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                kafka.getBootstrapServers(),
                "test-consumer-group-" + System.currentTimeMillis(),
                "true"
        );
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        JsonDeserializer<BankDataIngestionUnifiedEvent> jsonDeserializer =
                new JsonDeserializer<>(BankDataIngestionUnifiedEvent.class, false);
        jsonDeserializer.addTrustedPackages("*");

        DefaultKafkaConsumerFactory<String, BankDataIngestionUnifiedEvent> consumerFactory =
                new DefaultKafkaConsumerFactory<>(consumerProps, new StringDeserializer(), jsonDeserializer);

        return consumerFactory.createConsumer();
    }

    private List<BankDataIngestionUnifiedEvent> consumeAllEvents(
            Consumer<String, BankDataIngestionUnifiedEvent> consumer,
            Duration timeout) {
        List<BankDataIngestionUnifiedEvent> allEvents = new ArrayList<>();
        long endTime = System.currentTimeMillis() + timeout.toMillis();

        while (System.currentTimeMillis() < endTime) {
            ConsumerRecords<String, BankDataIngestionUnifiedEvent> records = consumer.poll(Duration.ofMillis(100));
            records.forEach(record -> allEvents.add(record.value()));

            // If we got some events and no new ones in the last poll, we're probably done
            if (!allEvents.isEmpty() && records.isEmpty()) {
                // Give it one more chance
                records = consumer.poll(Duration.ofMillis(500));
                records.forEach(record -> allEvents.add(record.value()));
                break;
            }
        }

        return allEvents;
    }

    private <T> T extractEvent(List<BankDataIngestionUnifiedEvent> events, String eventType, Class<T> eventClass) {
        return events.stream()
                .filter(e -> eventType.equals(e.getMetadata().get("eventType")))
                .findFirst()
                .map(e -> e.getContent().to(eventClass))
                .orElseThrow(() -> new AssertionError("Expected event " + eventType + " not found in events: " + events));
    }

    private <T> List<T> extractAllEvents(List<BankDataIngestionUnifiedEvent> events, String eventType, Class<T> eventClass) {
        return events.stream()
                .filter(e -> eventType.equals(e.getMetadata().get("eventType")))
                .map(e -> e.getContent().to(eventClass))
                .toList();
    }

    private String stageTransactionsForImport(String cashFlowId) {
        BankDataIngestionDto.StageTransactionsRequest stageRequest = BankDataIngestionDto.StageTransactionsRequest.builder()
                .transactions(List.of(
                        BankDataIngestionDto.BankTransactionJson.builder()
                                .bankTransactionId("txn-" + java.util.UUID.randomUUID())
                                .name("Test Transaction")
                                .description("Test")
                                .bankCategory("Zakupy kartą")
                                .amount(100.0)
                                .currency("PLN")
                                .type(Type.OUTFLOW)
                                .paidDate(ZonedDateTime.of(2021, 8, 15, 10, 0, 0, 0, ZoneOffset.UTC))
                                .build()
                ))
                .build();

        BankDataIngestionDto.StageTransactionsResponse stageResponse = bankDataIngestionRestController.stageTransactions(cashFlowId, stageRequest);
        return stageResponse.getStagingSessionId();
    }

    private void configureMappingsForStaging(String cashFlowId) {
        BankDataIngestionDto.ConfigureMappingsRequest request = BankDataIngestionDto.ConfigureMappingsRequest.builder()
                .mappings(List.of(
                        BankDataIngestionDto.MappingConfigJson.builder()
                                .bankCategoryName("Zakupy kartą")
                                .action(MappingAction.CREATE_NEW)
                                .targetCategoryName("Groceries")
                                .categoryType(Type.OUTFLOW)
                                .build(),
                        BankDataIngestionDto.MappingConfigJson.builder()
                                .bankCategoryName("Subskrypcje")
                                .action(MappingAction.CREATE_SUBCATEGORY)
                                .targetCategoryName("Netflix")
                                .parentCategoryName("Subscriptions")
                                .categoryType(Type.OUTFLOW)
                                .build(),
                        BankDataIngestionDto.MappingConfigJson.builder()
                                .bankCategoryName("Przelew przychodzący")
                                .action(MappingAction.CREATE_NEW)
                                .targetCategoryName("Salary")
                                .categoryType(Type.INFLOW)
                                .build()
                ))
                .build();

        bankDataIngestionRestController.configureMappings(cashFlowId, request);
    }

    private String createCashFlowWithHistory() {
        String cashFlowId = cashFlowRestController.createCashFlowWithHistory(
                CashFlowDto.CreateCashFlowWithHistoryJson.builder()
                        .userId("test-user-123")
                        .name("Test CashFlow")
                        .description("CashFlow for testing category mappings")
                        .bankAccount(new BankAccount(
                                new BankName("Test Bank"),
                                new BankAccountNumber("PL12345678901234567890123456", Currency.of("PLN")),
                                Money.zero("PLN")
                        ))
                        .startPeriod("2021-07")
                        .initialBalance(Money.of(10000.0, "PLN"))
                        .build()
        );

        log.info("Created CashFlow with ID: {}", cashFlowId);
        return cashFlowId;
    }
}
