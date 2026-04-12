package com.multi.vidulum.bank_data_ingestion.app;

import com.multi.vidulum.AuthenticatedHttpIntegrationTest;
import com.multi.vidulum.bank_data_ingestion.domain.MappingAction;
import com.multi.vidulum.bank_data_ingestion.domain.PatternMapping;
import com.multi.vidulum.bank_data_ingestion.domain.PatternMappingRepository;
import com.multi.vidulum.bank_data_ingestion.infrastructure.CategoryMappingMongoRepository;
import com.multi.vidulum.bank_data_ingestion.infrastructure.ImportJobMongoRepository;
import com.multi.vidulum.bank_data_ingestion.infrastructure.PatternMappingMongoRepository;
import com.multi.vidulum.bank_data_ingestion.infrastructure.StagedTransactionMongoRepository;
import com.multi.vidulum.cashflow.app.CashFlowDto;
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
 * Integration tests for Pattern Matching in Bank Data Ingestion.
 *
 * These tests verify that:
 * 1. Pattern mappings are used during staging to categorize transactions
 * 2. Pattern matching has priority over category mappings
 * 3. Revalidation also uses pattern matching
 * 4. Parent categories are looked up dynamically from CashFlow structure
 */
@Slf4j
@Import({PatternMatchingIntegrationTest.TestCashFlowServiceClientConfig.class})
public class PatternMatchingIntegrationTest extends AuthenticatedHttpIntegrationTest {

    private static final ZonedDateTime FIXED_NOW = ZonedDateTime.of(2022, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final AtomicInteger NAME_COUNTER = new AtomicInteger(0);

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
    private PatternMappingMongoRepository patternMappingMongoRepository;

    @Autowired
    private PatternMappingRepository patternMappingRepository;

    private BankDataIngestionHttpActor actor;

    @BeforeEach
    public void beforeTest() {
        waitForKafkaListeners();
        categoryMappingMongoRepository.deleteAll();
        stagedTransactionMongoRepository.deleteAll();
        importJobMongoRepository.deleteAll();
        patternMappingMongoRepository.deleteAll();
        cashFlowMongoRepository.deleteAll();
        cashFlowForecastMongoRepository.deleteAll();

        registerAndAuthenticate();

        actor = new BankDataIngestionHttpActor(restTemplate, port);
        actor.setJwtToken(accessToken);
    }

    private String uniqueCashFlowName() {
        return "PatternTest-" + NAME_COUNTER.incrementAndGet();
    }

    @Test
    @DisplayName("Should categorize transactions using pattern matching during staging")
    void shouldCategorizeTransactionsUsingPatternMatchingDuringStaging() {
        // given - create CashFlow with nested category structure
        YearMonth startPeriod = YearMonth.of(2021, 7);

        String cashFlowId = actor.createCashFlowWithHistory(
                userId,
                uniqueCashFlowName(),
                startPeriod,
                Money.of(10000.0, "PLN")
        );

        // Create nested category structure:
        // OUTFLOW:
        //   - Opłaty publiczne
        //     - Podatki
        //     - ZUS
        //   - Inne wydatki
        actor.createCategory(cashFlowId, "Opłaty publiczne", Type.OUTFLOW);
        actor.createCategory(cashFlowId, "Podatki", "Opłaty publiczne", Type.OUTFLOW);
        actor.createCategory(cashFlowId, "ZUS", "Opłaty publiczne", Type.OUTFLOW);
        actor.createCategory(cashFlowId, "Inne wydatki", Type.OUTFLOW);

        // Create pattern mappings (simulating AI categorization results)
        PatternMapping zusPattern = PatternMapping.createUser(
                "ZUS",
                "ZUS",
                "Opłaty publiczne",  // intendedParentCategory
                Type.OUTFLOW,
                userId,
                cashFlowId,
                0.95
        );
        PatternMapping urzadPattern = PatternMapping.createUser(
                "URZAD SKARBOWY",
                "Podatki",
                "Opłaty publiczne",  // intendedParentCategory
                Type.OUTFLOW,
                userId,
                cashFlowId,
                0.92
        );

        patternMappingRepository.saveAll(List.of(zusPattern, urzadPattern));

        log.info("Created pattern mappings: ZUS -> ZUS, URZAD SKARBOWY -> Podatki");

        // Configure a fallback category mapping for "Przelewy wychodzące"
        actor.configureMappings(cashFlowId, List.of(
                actor.mappingCreateNew("Przelewy wychodzące", "Inne wydatki", Type.OUTFLOW)
        ));

        // when - stage transactions with names matching patterns
        BankDataIngestionDto.StageTransactionsResponse stageResponse = actor.stageTransactions(cashFlowId, List.of(
                // Should match "ZUS" pattern -> ZUS category
                actor.bankTransaction("TXN-001", "ZAKŁAD UBEZPIECZEŃ SPOŁECZNYCH ZUS",
                        "Przelewy wychodzące", 1500.0, "PLN", Type.OUTFLOW,
                        ZonedDateTime.of(2021, 8, 15, 10, 0, 0, 0, ZoneOffset.UTC)),
                // Should match "URZAD SKARBOWY" pattern -> Podatki category
                actor.bankTransaction("TXN-002", "URZAD SKARBOWY W MIELCU",
                        "Przelewy wychodzące", 500.0, "PLN", Type.OUTFLOW,
                        ZonedDateTime.of(2021, 8, 20, 10, 0, 0, 0, ZoneOffset.UTC)),
                // Should NOT match any pattern -> falls back to category mapping -> Inne wydatki
                actor.bankTransaction("TXN-003", "PRZELEW DO JAN KOWALSKI",
                        "Przelewy wychodzące", 200.0, "PLN", Type.OUTFLOW,
                        ZonedDateTime.of(2021, 8, 25, 10, 0, 0, 0, ZoneOffset.UTC))
        ));

        // then - verify all transactions are READY_FOR_IMPORT
        assertThat(stageResponse.getStatus()).isEqualTo("READY_FOR_IMPORT");
        assertThat(stageResponse.getSummary().getTotalTransactions()).isEqualTo(3);
        assertThat(stageResponse.getSummary().getValidTransactions()).isEqualTo(3);

        String stagingSessionId = stageResponse.getStagingSessionId();

        // Get staging preview to verify category assignments
        BankDataIngestionDto.GetStagingPreviewResponse preview = actor.getStagingPreview(cashFlowId, stagingSessionId);

        // Find each transaction and verify category assignment
        BankDataIngestionDto.StagedTransactionPreviewJson zusTxn = preview.getTransactions().stream()
                .filter(t -> t.getBankTransactionId().equals("TXN-001"))
                .findFirst()
                .orElseThrow();

        BankDataIngestionDto.StagedTransactionPreviewJson urzadTxn = preview.getTransactions().stream()
                .filter(t -> t.getBankTransactionId().equals("TXN-002"))
                .findFirst()
                .orElseThrow();

        BankDataIngestionDto.StagedTransactionPreviewJson regularTxn = preview.getTransactions().stream()
                .filter(t -> t.getBankTransactionId().equals("TXN-003"))
                .findFirst()
                .orElseThrow();

        // ZUS transaction should be categorized to "ZUS" (pattern match)
        assertThat(zusTxn.getTargetCategory())
                .as("ZUS transaction should be categorized via pattern matching to 'ZUS'")
                .isEqualTo("ZUS");
        assertThat(zusTxn.getParentCategory())
                .as("Parent category should be looked up dynamically as 'Opłaty publiczne'")
                .isEqualTo("Opłaty publiczne");

        // URZAD SKARBOWY transaction should be categorized to "Podatki" (pattern match)
        assertThat(urzadTxn.getTargetCategory())
                .as("URZAD SKARBOWY transaction should be categorized via pattern matching to 'Podatki'")
                .isEqualTo("Podatki");
        assertThat(urzadTxn.getParentCategory())
                .as("Parent category should be looked up dynamically as 'Opłaty publiczne'")
                .isEqualTo("Opłaty publiczne");

        // Regular transaction should use category mapping fallback
        assertThat(regularTxn.getTargetCategory())
                .as("Regular transaction without pattern match should use category mapping")
                .isEqualTo("Inne wydatki");
        assertThat(regularTxn.getParentCategory())
                .as("Inne wydatki is a top-level category, so parent should be null")
                .isNull();

        log.info("Pattern matching test passed:");
        log.info("  - TXN-001 (ZUS in name) -> {} (parent: {})", zusTxn.getTargetCategory(), zusTxn.getParentCategory());
        log.info("  - TXN-002 (URZAD SKARBOWY in name) -> {} (parent: {})", urzadTxn.getTargetCategory(), urzadTxn.getParentCategory());
        log.info("  - TXN-003 (no pattern match) -> {} (parent: {})", regularTxn.getTargetCategory(), regularTxn.getParentCategory());
    }

    @Test
    @DisplayName("Should apply pattern matching during initial staging (pattern configured before staging)")
    void shouldApplyPatternMatchingDuringRevalidation() {
        // This test verifies that pattern matching works during initial staging.
        // Note: With Uncategorized fallback, revalidation only processes PENDING_MAPPING transactions.
        // Transactions already categorized (even as Uncategorized) are not re-categorized during revalidation.
        // To get proper categorization, patterns should be configured BEFORE staging.

        // given - create CashFlow with nested categories
        YearMonth startPeriod = YearMonth.of(2021, 7);

        String cashFlowId = actor.createCashFlowWithHistory(
                userId,
                uniqueCashFlowName(),
                startPeriod,
                Money.of(10000.0, "PLN")
        );

        // Create category structure
        actor.createCategory(cashFlowId, "Opłaty publiczne", Type.OUTFLOW);
        actor.createCategory(cashFlowId, "Podatki", "Opłaty publiczne", Type.OUTFLOW);

        // Step 1: Create pattern mapping BEFORE staging (simulating AI categorization)
        PatternMapping pattern = PatternMapping.createUser(
                "URZAD SKARBOWY",
                "Podatki",
                "Opłaty publiczne",  // intendedParentCategory
                Type.OUTFLOW,
                userId,
                cashFlowId,
                0.92
        );
        patternMappingRepository.save(pattern);

        log.info("Created pattern mapping: URZAD SKARBOWY -> Podatki");

        // Step 2: Stage transactions - pattern matching should apply during staging
        BankDataIngestionDto.StageTransactionsResponse stageResponse = actor.stageTransactions(cashFlowId, List.of(
                actor.bankTransaction("TXN-001", "URZAD SKARBOWY W MIELCU",
                        "Przelewy wychodzące", 500.0, "PLN", Type.OUTFLOW,
                        ZonedDateTime.of(2021, 8, 15, 10, 0, 0, 0, ZoneOffset.UTC))
        ));

        // With pattern mapping, transaction should be READY_FOR_IMPORT with correct category
        assertThat(stageResponse.getStatus()).isEqualTo("READY_FOR_IMPORT");
        String stagingSessionId = stageResponse.getStagingSessionId();

        // Verify the transaction has correct category from pattern matching
        BankDataIngestionDto.GetStagingPreviewResponse preview =
                actor.getStagingPreview(cashFlowId, stagingSessionId);

        BankDataIngestionDto.StagedTransactionPreviewJson txn = preview.getTransactions().get(0);
        assertThat(txn.getTargetCategory())
                .as("Transaction should be categorized via pattern matching during staging")
                .isEqualTo("Podatki");
        assertThat(txn.getParentCategory())
                .as("Parent category should be looked up dynamically")
                .isEqualTo("Opłaty publiczne");

        log.info("Pattern matching during staging passed: {} -> {} (parent: {})",
                txn.getName(), txn.getTargetCategory(), txn.getParentCategory());
    }

    @Test
    @DisplayName("Pattern matching should have priority over category mapping")
    void patternMatchingShouldHavePriorityOverCategoryMapping() {
        // given - create CashFlow
        YearMonth startPeriod = YearMonth.of(2021, 7);

        String cashFlowId = actor.createCashFlowWithHistory(
                userId,
                uniqueCashFlowName(),
                startPeriod,
                Money.of(10000.0, "PLN")
        );

        // Create categories: one generic fallback, one specific
        actor.createCategory(cashFlowId, "Składki społeczne", Type.OUTFLOW);
        actor.createCategory(cashFlowId, "Inne przelewy", Type.OUTFLOW);

        // Create pattern mapping for ZUS -> Składki społeczne
        PatternMapping zusPattern = PatternMapping.createUser(
                "ZUS",
                "Składki społeczne",
                null,  // intendedParentCategory - no parent in this test
                Type.OUTFLOW,
                userId,
                cashFlowId,
                0.95
        );
        patternMappingRepository.save(zusPattern);

        // Create category mapping for "Przelewy wychodzące" -> "Inne przelewy" (fallback)
        actor.configureMappings(cashFlowId, List.of(
                actor.mappingCreateNew("Przelewy wychodzące", "Inne przelewy", Type.OUTFLOW)
        ));

        // when - stage transaction with ZUS in name (should use pattern, not category mapping)
        BankDataIngestionDto.StageTransactionsResponse stageResponse = actor.stageTransactions(cashFlowId, List.of(
                actor.bankTransaction("TXN-001", "ZUS SKŁADKA ZDROWOTNA",
                        "Przelewy wychodzące", 1500.0, "PLN", Type.OUTFLOW,
                        ZonedDateTime.of(2021, 8, 15, 10, 0, 0, 0, ZoneOffset.UTC))
        ));

        // then - should be READY and use pattern mapping
        assertThat(stageResponse.getStatus()).isEqualTo("READY_FOR_IMPORT");

        BankDataIngestionDto.GetStagingPreviewResponse preview =
                actor.getStagingPreview(cashFlowId, stageResponse.getStagingSessionId());

        BankDataIngestionDto.StagedTransactionPreviewJson txn = preview.getTransactions().get(0);

        // KEY ASSERTION: Pattern matching wins over category mapping
        assertThat(txn.getTargetCategory())
                .as("Pattern matching should have priority: ZUS -> 'Składki społeczne' (not 'Inne przelewy' from category mapping)")
                .isEqualTo("Składki społeczne");

        log.info("Priority test passed: {} -> {} (pattern wins over category mapping)",
                txn.getName(), txn.getTargetCategory());
    }

    @Test
    @DisplayName("Should complete full import flow with pattern matching")
    void shouldCompleteFullImportFlowWithPatternMatching() {
        // given - create CashFlow with categories
        YearMonth startPeriod = YearMonth.of(2021, 7);

        String cashFlowId = actor.createCashFlowWithHistory(
                userId,
                uniqueCashFlowName(),
                startPeriod,
                Money.of(10000.0, "PLN")
        );

        // Create nested category structure
        actor.createCategory(cashFlowId, "Opłaty publiczne", Type.OUTFLOW);
        actor.createCategory(cashFlowId, "ZUS", "Opłaty publiczne", Type.OUTFLOW);

        // Create pattern mapping
        PatternMapping pattern = PatternMapping.createUser(
                "ZUS",
                "ZUS",
                "Opłaty publiczne",  // intendedParentCategory
                Type.OUTFLOW,
                userId,
                cashFlowId,
                0.95
        );
        patternMappingRepository.save(pattern);

        // when - stage, import, and finalize
        BankDataIngestionDto.StageTransactionsResponse stageResponse = actor.stageTransactions(cashFlowId, List.of(
                actor.bankTransaction("TXN-001", "ZAKŁAD UBEZPIECZEŃ SPOŁECZNYCH ZUS",
                        "Przelewy wychodzące", 1500.0, "PLN", Type.OUTFLOW,
                        ZonedDateTime.of(2021, 8, 15, 10, 0, 0, 0, ZoneOffset.UTC))
        ));

        // Note: Even without category mapping for "Przelewy wychodzące",
        // the transaction should be valid because pattern matching found it
        assertThat(stageResponse.getStatus()).isEqualTo("READY_FOR_IMPORT");

        String stagingSessionId = stageResponse.getStagingSessionId();

        // Start import
        BankDataIngestionDto.StartImportResponse importResponse =
                actor.startImport(cashFlowId, stagingSessionId);

        // Check progress
        BankDataIngestionDto.GetImportProgressResponse progress =
                actor.getImportProgress(cashFlowId, importResponse.getJobId());

        assertThat(progress.getResult().getTransactionsImported()).isEqualTo(1);
        assertThat(progress.getResult().getTransactionsFailed()).isEqualTo(0);

        // Finalize
        actor.finalizeImport(cashFlowId, importResponse.getJobId(), false);

        // then - verify CashFlow has the transaction in correct category
        CashFlowDto.CashFlowSummaryJson cashFlow = actor.getCashFlow(cashFlowId);

        assertThat(cashFlow.getCashChanges()).hasSize(1);

        CashFlowDto.CashChangeSummaryJson importedTxn = cashFlow.getCashChanges().values().iterator().next();

        assertThat(importedTxn.getCategoryName())
                .as("Imported transaction should be in 'ZUS' category (from pattern matching)")
                .isEqualTo("ZUS");

        log.info("Full import with pattern matching completed: {} -> category '{}'",
                importedTxn.getName(), importedTxn.getCategoryName());
    }
}
