package com.multi.vidulum.bank_data_ingestion.app;

import com.multi.vidulum.AuthenticatedHttpIntegrationTest;
import com.multi.vidulum.bank_data_ingestion.domain.AiCategorizationStatus;
import com.multi.vidulum.bank_data_ingestion.domain.StagingSessionStatus;
import com.multi.vidulum.bank_data_ingestion.infrastructure.CategoryMappingMongoRepository;
import com.multi.vidulum.bank_data_ingestion.infrastructure.ImportJobMongoRepository;
import com.multi.vidulum.bank_data_ingestion.infrastructure.StagedTransactionMongoRepository;
import com.multi.vidulum.bank_data_ingestion.infrastructure.StagingSessionMongoRepository;
import com.multi.vidulum.bank_data_ingestion.infrastructure.entity.StagingSessionEntity;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.cashflow.infrastructure.CashFlowMongoRepository;
import com.multi.vidulum.cashflow_forecast_processor.infrastructure.CashFlowForecastMongoRepository;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying StagingSessionEntity lifecycle.
 * Tests that the staging session entity is properly created, updated, and
 * transitions through the correct states during the import process.
 *
 * This test ensures:
 * 1. Session entity is created with correct metadata (language, bank, country)
 * 2. Status transitions work correctly through the lifecycle
 * 3. AI categorization status is tracked
 * 4. Import job tracking works correctly
 */
@Slf4j
@Import({StagingSessionLifecycleIntegrationTest.TestCashFlowServiceClientConfig.class})
public class StagingSessionLifecycleIntegrationTest extends AuthenticatedHttpIntegrationTest {

    // FixedClockConfig sets clock to 2022-01-01T00:00:00Z
    private static final ZonedDateTime FIXED_NOW = ZonedDateTime.of(2022, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private static final AtomicInteger NAME_COUNTER = new AtomicInteger(0);

    private String uniqueCashFlowName() {
        return "Lifecycle-Test-" + NAME_COUNTER.incrementAndGet();
    }

    @TestConfiguration
    static class TestCashFlowServiceClientConfig {
        @Bean
        public CashFlowServiceClient cashFlowServiceClient(
                QueryGateway queryGateway,
                @Lazy CommandGateway commandGateway) {
            return new TestCashFlowServiceClient(queryGateway, commandGateway);
        }
    }

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

    @Autowired
    private StagingSessionMongoRepository stagingSessionRepository;

    private BankDataIngestionHttpActor actor;

    @BeforeEach
    public void beforeTest() {
        waitForKafkaListeners();
        categoryMappingMongoRepository.deleteAll();
        stagedTransactionMongoRepository.deleteAll();
        importJobMongoRepository.deleteAll();
        stagingSessionRepository.deleteAll();
        cashFlowMongoRepository.deleteAll();
        cashFlowForecastMongoRepository.deleteAll();

        // Register and authenticate to get JWT token
        registerAndAuthenticate();

        // Create actor with JWT token
        actor = new BankDataIngestionHttpActor(restTemplate, port);
        actor.setJwtToken(accessToken);
    }

    @Test
    @DisplayName("Should create StagingSessionEntity when staging transactions via REST API")
    void shouldCreateStagingSessionEntityWhenStagingTransactions() {
        // given
        YearMonth startPeriod = YearMonth.of(2021, 7);
        String cashFlowId = actor.createCashFlowWithHistory(
                userId,
                uniqueCashFlowName(),
                startPeriod,
                Money.of(10000.0, "PLN")
        );

        // Configure mappings
        actor.configureMappings(cashFlowId, List.of(
                actor.mappingCreateNew("Zakupy", "Groceries", Type.OUTFLOW)
        ));

        // when - stage transactions
        BankDataIngestionDto.StageTransactionsResponse stageResponse = actor.stageTransactions(cashFlowId, List.of(
                actor.bankTransaction("txn-001", "Biedronka", "Zakupy", 150.50, "PLN", Type.OUTFLOW,
                        ZonedDateTime.of(2021, 8, 15, 10, 0, 0, 0, ZoneOffset.UTC)),
                actor.bankTransaction("txn-002", "Lidl", "Zakupy", 89.99, "PLN", Type.OUTFLOW,
                        ZonedDateTime.of(2021, 8, 20, 10, 0, 0, 0, ZoneOffset.UTC))
        ));

        String stagingSessionId = stageResponse.getStagingSessionId();

        // then - verify session entity was created
        StagingSessionEntity sessionEntity = stagingSessionRepository
                .findBySessionId(stagingSessionId)
                .orElse(null);

        assertThat(sessionEntity)
                .as("StagingSessionEntity should be created when staging transactions")
                .isNotNull();

        assertThat(sessionEntity.getSessionId()).isEqualTo(stagingSessionId);
        assertThat(sessionEntity.getCashFlowId()).isEqualTo(cashFlowId);
        assertThat(sessionEntity.getStatus()).isEqualTo(StagingSessionStatus.READY_FOR_IMPORT);
        assertThat(sessionEntity.getAiCategorizationStatus()).isEqualTo(AiCategorizationStatus.NOT_STARTED);
        assertThat(sessionEntity.getTotalTransactions()).isEqualTo(2);
        assertThat(sessionEntity.getValidTransactions()).isEqualTo(2);
        assertThat(sessionEntity.getInvalidTransactions()).isEqualTo(0);
        assertThat(sessionEntity.getDuplicateTransactions()).isEqualTo(0);
        assertThat(sessionEntity.getUnmappedTransactions()).isEqualTo(0);
        assertThat(sessionEntity.getCreatedAt()).isNotNull();
        assertThat(sessionEntity.getExpiresAt()).isNotNull();

        log.info("Staging session entity created: id={}, status={}, total={}, valid={}",
                sessionEntity.getSessionId(),
                sessionEntity.getStatus(),
                sessionEntity.getTotalTransactions(),
                sessionEntity.getValidTransactions());
    }

    @Test
    @DisplayName("Should track session status transitions through unmapped -> ready -> importing -> completed")
    void shouldTrackSessionStatusTransitionsThroughFullLifecycle() {
        // given
        YearMonth startPeriod = YearMonth.of(2021, 7);
        String cashFlowId = actor.createCashFlowWithHistory(
                userId,
                uniqueCashFlowName(),
                startPeriod,
                Money.of(10000.0, "PLN")
        );

        // Stage transactions WITHOUT mappings - should result in HAS_UNMAPPED_CATEGORIES
        BankDataIngestionDto.StageTransactionsResponse stageResponse = actor.stageTransactions(cashFlowId, List.of(
                actor.bankTransaction("txn-001", "Biedronka", "Zakupy", 150.50, "PLN", Type.OUTFLOW,
                        ZonedDateTime.of(2021, 8, 15, 10, 0, 0, 0, ZoneOffset.UTC))
        ));

        String stagingSessionId = stageResponse.getStagingSessionId();

        // Verify initial state: HAS_UNMAPPED_CATEGORIES
        StagingSessionEntity session1 = stagingSessionRepository.findBySessionId(stagingSessionId).orElseThrow();
        assertThat(session1.getStatus()).isEqualTo(StagingSessionStatus.HAS_UNMAPPED_CATEGORIES);
        assertThat(session1.getUnmappedTransactions()).isEqualTo(1);

        log.info("Step 1 - Initial staging: status={}, unmapped={}",
                session1.getStatus(), session1.getUnmappedTransactions());

        // Step 2: Configure mappings and revalidate
        actor.configureMappings(cashFlowId, List.of(
                actor.mappingCreateNew("Zakupy", "Groceries", Type.OUTFLOW)
        ));
        actor.revalidateStaging(cashFlowId, stagingSessionId);

        // Verify transition to READY_FOR_IMPORT
        StagingSessionEntity session2 = stagingSessionRepository.findBySessionId(stagingSessionId).orElseThrow();
        assertThat(session2.getStatus()).isEqualTo(StagingSessionStatus.READY_FOR_IMPORT);
        assertThat(session2.getUnmappedTransactions()).isEqualTo(0);

        log.info("Step 2 - After revalidation: status={}, unmapped={}",
                session2.getStatus(), session2.getUnmappedTransactions());

        // Step 3: Start import
        BankDataIngestionDto.StartImportResponse importResponse = actor.startImport(cashFlowId, stagingSessionId);
        String jobId = importResponse.getJobId();

        // Note: Import may complete synchronously, so we check for IMPORTING or COMPLETED
        StagingSessionEntity session3 = stagingSessionRepository.findBySessionId(stagingSessionId).orElseThrow();
        assertThat(session3.getStatus()).isIn(StagingSessionStatus.IMPORTING, StagingSessionStatus.COMPLETED);
        assertThat(session3.getImportJobId()).isNotNull();

        log.info("Step 3 - After starting import: status={}, jobId={}",
                session3.getStatus(), session3.getImportJobId());

        // Step 4: Wait for completion and verify
        actor.getImportProgress(cashFlowId, jobId);
        actor.finalizeImport(cashFlowId, jobId, false);

        StagingSessionEntity session4 = stagingSessionRepository.findBySessionId(stagingSessionId).orElseThrow();
        assertThat(session4.getStatus()).isEqualTo(StagingSessionStatus.COMPLETED);
        assertThat(session4.getImportCompletedAt()).isNotNull();
        assertThat(session4.getImportedTransactionsCount()).isEqualTo(1);

        log.info("Step 4 - After completion: status={}, importedCount={}, completedAt={}",
                session4.getStatus(),
                session4.getImportedTransactionsCount(),
                session4.getImportCompletedAt());
    }

    @Test
    @DisplayName("Should track session with force-uncategorized flow")
    void shouldTrackSessionWithForceUncategorizedFlow() {
        // given
        YearMonth startPeriod = YearMonth.of(2021, 7);
        String cashFlowId = actor.createCashFlowWithHistory(
                userId,
                uniqueCashFlowName(),
                startPeriod,
                Money.of(10000.0, "PLN")
        );

        // Stage transactions WITHOUT mappings
        BankDataIngestionDto.StageTransactionsResponse stageResponse = actor.stageTransactions(cashFlowId, List.of(
                actor.bankTransaction("txn-001", "Random Store", "Unknown Category", 100.0, "PLN", Type.OUTFLOW,
                        ZonedDateTime.of(2021, 8, 15, 10, 0, 0, 0, ZoneOffset.UTC)),
                actor.bankTransaction("txn-002", "Another Store", "Another Unknown", 200.0, "PLN", Type.OUTFLOW,
                        ZonedDateTime.of(2021, 8, 20, 10, 0, 0, 0, ZoneOffset.UTC))
        ));

        String stagingSessionId = stageResponse.getStagingSessionId();

        // Verify initial state: HAS_UNMAPPED_CATEGORIES
        StagingSessionEntity session1 = stagingSessionRepository.findBySessionId(stagingSessionId).orElseThrow();
        assertThat(session1.getStatus()).isEqualTo(StagingSessionStatus.HAS_UNMAPPED_CATEGORIES);
        assertThat(session1.getUnmappedTransactions()).isEqualTo(2);

        log.info("Before force-uncategorized: status={}, unmapped={}",
                session1.getStatus(), session1.getUnmappedTransactions());

        // Force all to uncategorized
        actor.forceUncategorized(cashFlowId, stagingSessionId);

        // Verify transition to READY_FOR_IMPORT
        StagingSessionEntity session2 = stagingSessionRepository.findBySessionId(stagingSessionId).orElseThrow();
        assertThat(session2.getStatus()).isEqualTo(StagingSessionStatus.READY_FOR_IMPORT);
        assertThat(session2.getUnmappedTransactions()).isEqualTo(0);
        assertThat(session2.getAiCategorizationStatus()).isEqualTo(AiCategorizationStatus.SKIPPED);

        log.info("After force-uncategorized: status={}, unmapped={}, aiStatus={}",
                session2.getStatus(),
                session2.getUnmappedTransactions(),
                session2.getAiCategorizationStatus());
    }

    @Test
    @DisplayName("Should create session with metadata when uploading CSV from resources")
    void shouldCreateSessionWithMetadataWhenUploadingCsv() {
        // given
        YearMonth startPeriod = YearMonth.of(2021, 7);
        String cashFlowId = actor.createCashFlowWithHistory(
                userId,
                uniqueCashFlowName(),
                startPeriod,
                Money.of(10000.0, "PLN")
        );

        // Configure all mappings needed for the CSV
        actor.configureMappings(cashFlowId, List.of(
                actor.mappingCreateNew("Wpływy regularne", "Salary", Type.INFLOW),
                actor.mappingCreateNew("Mieszkanie", "Rent", Type.OUTFLOW),
                actor.mappingCreateNew("Zakupy kartą", "Groceries", Type.OUTFLOW),
                actor.mappingCreateNew("Rachunki", "Utilities", Type.OUTFLOW),
                actor.mappingCreateNew("Rozrywka", "Entertainment", Type.OUTFLOW),
                actor.mappingCreateNew("Transport", "Transportation", Type.OUTFLOW)
        ));

        // when - upload CSV file
        BankDataIngestionDto.UploadCsvResponse uploadResponse = actor.uploadCsv(
                cashFlowId,
                "bank-data-ingestion/historical-transactions.csv"
        );

        String stagingSessionId = uploadResponse.getStagingResult().getStagingSessionId();

        // then - verify session entity has file name metadata
        StagingSessionEntity sessionEntity = stagingSessionRepository
                .findBySessionId(stagingSessionId)
                .orElse(null);

        assertThat(sessionEntity)
                .as("StagingSessionEntity should be created for CSV upload")
                .isNotNull();

        assertThat(sessionEntity.getOriginalFileName())
                .as("Original file name should be stored")
                .isEqualTo("historical-transactions.csv");

        assertThat(sessionEntity.getStatus())
                .as("All transactions should be mapped, so status should be READY_FOR_IMPORT")
                .isEqualTo(StagingSessionStatus.READY_FOR_IMPORT);

        assertThat(sessionEntity.getTotalTransactions()).isEqualTo(23);

        log.info("CSV upload session created: id={}, fileName={}, status={}, total={}",
                sessionEntity.getSessionId(),
                sessionEntity.getOriginalFileName(),
                sessionEntity.getStatus(),
                sessionEntity.getTotalTransactions());
    }

    @Test
    @DisplayName("Should list sessions using StagingSessionEntity for efficient queries")
    void shouldListSessionsUsingStagingSessionEntity() {
        // given
        YearMonth startPeriod = YearMonth.of(2021, 7);
        String cashFlowId = actor.createCashFlowWithHistory(
                userId,
                uniqueCashFlowName(),
                startPeriod,
                Money.of(10000.0, "PLN")
        );

        // Configure mappings
        actor.configureMappings(cashFlowId, List.of(
                actor.mappingCreateNew("Zakupy", "Groceries", Type.OUTFLOW)
        ));

        // Create multiple staging sessions
        BankDataIngestionDto.StageTransactionsResponse session1Response = actor.stageTransactions(cashFlowId, List.of(
                actor.bankTransaction("txn-001", "Biedronka", "Zakupy", 150.50, "PLN", Type.OUTFLOW,
                        ZonedDateTime.of(2021, 8, 15, 10, 0, 0, 0, ZoneOffset.UTC))
        ));

        BankDataIngestionDto.StageTransactionsResponse session2Response = actor.stageTransactions(cashFlowId, List.of(
                actor.bankTransaction("txn-002", "Lidl", "Zakupy", 89.99, "PLN", Type.OUTFLOW,
                        ZonedDateTime.of(2021, 8, 20, 10, 0, 0, 0, ZoneOffset.UTC))
        ));

        // when - list sessions
        BankDataIngestionDto.ListStagingSessionsResponse listResponse = actor.listStagingSessions(cashFlowId);

        // then - verify both sessions are listed
        assertThat(listResponse.getStagingSessions()).hasSize(2);
        assertThat(listResponse.isHasPendingImport()).isTrue();

        // Verify both sessions are in the list (order is non-deterministic when createdAt is same)
        List<String> sessionIds = listResponse.getStagingSessions().stream()
                .map(BankDataIngestionDto.StagingSessionSummaryJson::getStagingSessionId)
                .toList();
        assertThat(sessionIds).containsExactlyInAnyOrder(
                session1Response.getStagingSessionId(),
                session2Response.getStagingSessionId()
        );

        // Verify session counts are populated from entity (not computed)
        BankDataIngestionDto.StagingSessionSummaryJson sessionSummary = listResponse.getStagingSessions().get(0);
        assertThat(sessionSummary.getCounts().getTotalTransactions()).isEqualTo(1);
        assertThat(sessionSummary.getCounts().getValidTransactions()).isEqualTo(1);
        assertThat(sessionSummary.getStatus()).isEqualTo("READY_FOR_IMPORT");

        // Verify entity exists in repository
        List<StagingSessionEntity> entities = stagingSessionRepository.findByCashFlowId(cashFlowId);
        assertThat(entities).hasSize(2);

        log.info("Listed {} sessions for CashFlow {}: {}",
                listResponse.getStagingSessions().size(),
                cashFlowId,
                listResponse.getStagingSessions().stream()
                        .map(s -> s.getStagingSessionId() + "=" + s.getStatus())
                        .toList());
    }
}
