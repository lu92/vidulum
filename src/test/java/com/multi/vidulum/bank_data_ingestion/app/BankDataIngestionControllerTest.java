package com.multi.vidulum.bank_data_ingestion.app;

import com.multi.vidulum.JsonFormatter;
import com.multi.vidulum.bank_data_ingestion.domain.MappingAction;
import com.multi.vidulum.bank_data_ingestion.infrastructure.CategoryMappingMongoRepository;
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

    private JsonFormatter jsonFormatter = new JsonFormatter();

    @BeforeEach
    public void beforeTest() {
        kafkaListenerEndpointRegistry.getListenerContainers().forEach(
                messageListenerContainer -> ContainerTestUtils.waitForAssignment(messageListenerContainer, 1));
        categoryMappingMongoRepository.deleteAll();
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
