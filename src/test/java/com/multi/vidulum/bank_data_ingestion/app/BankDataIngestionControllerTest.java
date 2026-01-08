package com.multi.vidulum.bank_data_ingestion.app;

import com.multi.vidulum.JsonFormatter;
import com.multi.vidulum.bank_data_ingestion.domain.MappingAction;
import com.multi.vidulum.bank_data_ingestion.infrastructure.CategoryMappingMongoRepository;
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
import com.multi.vidulum.config.FixedClockConfig;
import com.multi.vidulum.portfolio.app.PortfolioAppConfig;
import com.multi.vidulum.trading.app.TradingAppConfig;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTest(classes = FixedClockConfig.class)
@Import({PortfolioAppConfig.class, TradingAppConfig.class})
@Testcontainers
@DirtiesContext
public class BankDataIngestionControllerTest {

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

    private JsonFormatter jsonFormatter = new JsonFormatter();

    @BeforeEach
    public void beforeTest() {
        kafkaListenerEndpointRegistry.getListenerContainers().forEach(
                messageListenerContainer -> ContainerTestUtils.waitForAssignment(messageListenerContainer, 1));
        categoryMappingMongoRepository.deleteAll();
        stagedTransactionMongoRepository.deleteAll();
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
                                .action(MappingAction.MAP_TO_EXISTING)
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
                                .action(MappingAction.MAP_TO_EXISTING)
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

        BankDataIngestionDto.ConfigureMappingsResponse response =
                bankDataIngestionRestController.configureMappings(cashFlowId, request);

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
                                .action(MappingAction.MAP_TO_EXISTING)
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
                                .action(MappingAction.MAP_TO_EXISTING)
                                .targetCategoryName("Shopping")
                                .categoryType(Type.OUTFLOW)
                                .build()
                ))
                .build();

        BankDataIngestionDto.ConfigureMappingsResponse response =
                bankDataIngestionRestController.configureMappings(cashFlowId, updateRequest);

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
                                .action(MappingAction.MAP_TO_EXISTING)
                                .targetCategoryName("Groceries")
                                .categoryType(Type.OUTFLOW)
                                .build(),
                        BankDataIngestionDto.MappingConfigJson.builder()
                                .bankCategoryName("Salary")
                                .action(MappingAction.MAP_TO_EXISTING)
                                .targetCategoryName("Income")
                                .categoryType(Type.INFLOW)
                                .build()
                ))
                .build();

        bankDataIngestionRestController.configureMappings(cashFlowId, request);

        // when: get mappings
        BankDataIngestionDto.GetMappingsResponse response =
                bankDataIngestionRestController.getMappings(cashFlowId);

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
                                .action(MappingAction.MAP_TO_EXISTING)
                                .targetCategoryName("Groceries")
                                .categoryType(Type.OUTFLOW)
                                .build(),
                        BankDataIngestionDto.MappingConfigJson.builder()
                                .bankCategoryName("Salary")
                                .action(MappingAction.MAP_TO_EXISTING)
                                .targetCategoryName("Income")
                                .categoryType(Type.INFLOW)
                                .build()
                ))
                .build();

        BankDataIngestionDto.ConfigureMappingsResponse configureResponse =
                bankDataIngestionRestController.configureMappings(cashFlowId, request);

        String mappingIdToDelete = configureResponse.getMappings().get(0).getMappingId();

        // when: delete the first mapping
        BankDataIngestionDto.DeleteMappingResponse deleteResponse =
                bankDataIngestionRestController.deleteMapping(cashFlowId, mappingIdToDelete);

        log.info("Delete mapping response:\n{}", jsonFormatter.formatToPrettyJson(deleteResponse));

        // then: verify the mapping was deleted
        assertThat(deleteResponse.isDeleted()).isTrue();
        assertThat(deleteResponse.getMappingId()).isEqualTo(mappingIdToDelete);

        // verify only one mapping remains
        BankDataIngestionDto.GetMappingsResponse getMappingsResponse =
                bankDataIngestionRestController.getMappings(cashFlowId);
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
                                .action(MappingAction.MAP_TO_EXISTING)
                                .targetCategoryName("Groceries")
                                .categoryType(Type.OUTFLOW)
                                .build(),
                        BankDataIngestionDto.MappingConfigJson.builder()
                                .bankCategoryName("Salary")
                                .action(MappingAction.MAP_TO_EXISTING)
                                .targetCategoryName("Income")
                                .categoryType(Type.INFLOW)
                                .build()
                ))
                .build();

        bankDataIngestionRestController.configureMappings(cashFlowId, request);

        // when: delete all mappings
        BankDataIngestionDto.DeleteAllMappingsResponse deleteResponse =
                bankDataIngestionRestController.deleteAllMappings(cashFlowId);

        log.info("Delete all mappings response:\n{}", jsonFormatter.formatToPrettyJson(deleteResponse));

        // then: verify all mappings were deleted
        assertThat(deleteResponse.isDeleted()).isTrue();
        assertThat(deleteResponse.getDeletedCount()).isEqualTo(2);

        // verify no mappings remain
        BankDataIngestionDto.GetMappingsResponse getMappingsResponse =
                bankDataIngestionRestController.getMappings(cashFlowId);
        assertThat(getMappingsResponse.getMappingsCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should return empty list when no mappings configured")
    void shouldReturnEmptyListWhenNoMappings() {
        // given: create a CashFlow without any mappings
        String cashFlowId = createCashFlowWithHistory();

        // when: get mappings
        BankDataIngestionDto.GetMappingsResponse response =
                bankDataIngestionRestController.getMappings(cashFlowId);

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
                                .action(MappingAction.MAP_TO_EXISTING)
                                .targetCategoryName("Transfers In")
                                .categoryType(Type.INFLOW)
                                .build()
                ))
                .build();

        // when: configure mappings
        BankDataIngestionDto.ConfigureMappingsResponse response =
                bankDataIngestionRestController.configureMappings(cashFlowId, request);

        log.info("Configure same bank category with different types response:\n{}",
                jsonFormatter.formatToPrettyJson(response));

        // then: both mappings should be created (unique by bankCategoryName + categoryType)
        assertThat(response.getMappingsConfigured()).isEqualTo(2);
        assertThat(response.getMappings()).allMatch(m -> m.getStatus().equals("CREATED"));

        // verify both are retrievable
        BankDataIngestionDto.GetMappingsResponse getMappingsResponse =
                bankDataIngestionRestController.getMappings(cashFlowId);
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

        BankDataIngestionDto.StageTransactionsResponse response =
                bankDataIngestionRestController.stageTransactions(cashFlowId, request);

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

        BankDataIngestionDto.StageTransactionsResponse response =
                bankDataIngestionRestController.stageTransactions(cashFlowId, request);

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

        BankDataIngestionDto.StageTransactionsResponse response =
                bankDataIngestionRestController.stageTransactions(cashFlowId, request);

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

        BankDataIngestionDto.StageTransactionsResponse stageResponse =
                bankDataIngestionRestController.stageTransactions(cashFlowId, stageRequest);

        String stagingSessionId = stageResponse.getStagingSessionId();

        // when: get staging preview
        BankDataIngestionDto.GetStagingPreviewResponse response =
                bankDataIngestionRestController.getStagingPreview(cashFlowId, stagingSessionId);

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
        BankDataIngestionDto.GetStagingPreviewResponse response =
                bankDataIngestionRestController.getStagingPreview(cashFlowId, "non-existent-session-id");

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

        BankDataIngestionDto.StageTransactionsResponse stageResponse =
                bankDataIngestionRestController.stageTransactions(cashFlowId, stageRequest);

        String stagingSessionId = stageResponse.getStagingSessionId();

        // when: delete staging session
        BankDataIngestionDto.DeleteStagingSessionResponse response =
                bankDataIngestionRestController.deleteStagingSession(cashFlowId, stagingSessionId);

        log.info("Delete staging session response - deleted: {}, count: {}", response.isDeleted(), response.getDeletedCount());

        // then: verify deletion
        assertThat(response.isDeleted()).isTrue();
        assertThat(response.getDeletedCount()).isEqualTo(2);
        assertThat(response.getStagingSessionId()).isEqualTo(stagingSessionId);

        // verify session is gone
        BankDataIngestionDto.GetStagingPreviewResponse previewResponse =
                bankDataIngestionRestController.getStagingPreview(cashFlowId, stagingSessionId);
        assertThat(previewResponse.getStatus()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("Should handle delete of non-existent staging session gracefully")
    void shouldHandleDeleteOfNonExistentSession() {
        // given: create a CashFlow
        String cashFlowId = createCashFlowWithHistory();

        // when: delete non-existent staging session
        BankDataIngestionDto.DeleteStagingSessionResponse response =
                bankDataIngestionRestController.deleteStagingSession(cashFlowId, "non-existent-session-id");

        log.info("Delete non-existent staging session response - deleted: {}, count: {}", response.isDeleted(), response.getDeletedCount());

        // then: verify no error, just not deleted
        assertThat(response.isDeleted()).isFalse();
        assertThat(response.getDeletedCount()).isEqualTo(0);
    }

    // Helper method to configure mappings for staging tests
    private void configureMappingsForStaging(String cashFlowId) {
        BankDataIngestionDto.ConfigureMappingsRequest request = BankDataIngestionDto.ConfigureMappingsRequest.builder()
                .mappings(List.of(
                        BankDataIngestionDto.MappingConfigJson.builder()
                                .bankCategoryName("Zakupy kartą")
                                .action(MappingAction.MAP_TO_EXISTING)
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
                                .action(MappingAction.MAP_TO_EXISTING)
                                .targetCategoryName("Salary")
                                .categoryType(Type.INFLOW)
                                .build()
                ))
                .build();

        bankDataIngestionRestController.configureMappings(cashFlowId, request);
    }

    // Helper method to create a CashFlow with history
    private String createCashFlowWithHistory() {
        CashFlowDto.CreateCashFlowWithHistoryJson request = CashFlowDto.CreateCashFlowWithHistoryJson.builder()
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
                .build();

        return cashFlowRestController.createCashFlowWithHistory(request);
    }
}
