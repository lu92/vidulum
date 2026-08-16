package com.multi.vidulum.bank_data_ingestion.app;

import com.multi.vidulum.AuthenticatedHttpIntegrationTest;
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
 * Integration tests for Direct Category Matching (Priority 0) in Bank Data Ingestion.
 *
 * These tests verify that:
 * 1. BankCategory that matches an existing CashFlow category name is automatically categorized (Priority 0)
 * 2. Direct match has highest priority (before pattern matching and category mapping)
 * 3. Works for both INFLOW and OUTFLOW categories
 * 4. Works with nested categories (subcategories)
 * 5. Revalidation also uses direct matching
 *
 * This is particularly useful for banks like Pekao that provide detailed category names
 * (e.g., "Artykuły spożywcze", "Transport") which can directly match user categories
 * without requiring any mapping configuration.
 */
@Slf4j
@Import({DirectCategoryMatchIntegrationTest.TestCashFlowServiceClientConfig.class})
public class DirectCategoryMatchIntegrationTest extends AuthenticatedHttpIntegrationTest {

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
        return "DirectMatchTest-" + NAME_COUNTER.incrementAndGet();
    }

    @Test
    @DisplayName("Should auto-categorize when bankCategory matches existing CashFlow category (Priority 0)")
    void shouldAutoCategorizeWhenBankCategoryMatchesExistingCategory() {
        // given - create CashFlow with categories that match Pekao bank categories
        YearMonth startPeriod = YearMonth.of(2021, 7);

        String cashFlowId = actor.createCashFlowWithHistory(
                userId,
                uniqueCashFlowName(),
                startPeriod,
                Money.of(10000.0, "PLN")
        );

        // Create categories matching Pekao bank category names
        actor.createCategory(cashFlowId, "Artykuły spożywcze", Type.OUTFLOW);
        actor.createCategory(cashFlowId, "Transport", Type.OUTFLOW);
        actor.createCategory(cashFlowId, "Wynagrodzenie", Type.INFLOW);

        log.info("Created CashFlow with categories: Artykuły spożywcze, Transport, Wynagrodzenie");

        // when - stage transactions where bankCategory matches existing category names
        // NO mapping configuration needed!
        BankDataIngestionDto.StageTransactionsResponse stageResponse = actor.stageTransactions(cashFlowId, List.of(
                // bankCategory = "Artykuły spożywcze" should match existing category
                actor.bankTransaction("TXN-001", "BIEDRONKA WARSZAWA",
                        "Artykuły spożywcze", 150.0, "PLN", Type.OUTFLOW,
                        ZonedDateTime.of(2021, 8, 15, 10, 0, 0, 0, ZoneOffset.UTC)),
                // bankCategory = "Transport" should match existing category
                actor.bankTransaction("TXN-002", "UBER TRIP",
                        "Transport", 45.0, "PLN", Type.OUTFLOW,
                        ZonedDateTime.of(2021, 8, 16, 10, 0, 0, 0, ZoneOffset.UTC)),
                // bankCategory = "Wynagrodzenie" should match existing INFLOW category
                actor.bankTransaction("TXN-003", "FIRMA XYZ SP ZOO",
                        "Wynagrodzenie", 8500.0, "PLN", Type.INFLOW,
                        ZonedDateTime.of(2021, 8, 10, 10, 0, 0, 0, ZoneOffset.UTC))
        ));

        // then - all transactions should be READY_FOR_IMPORT without any mapping configuration
        assertThat(stageResponse.getStatus())
                .as("Transactions should be READY without needing any mapping configuration")
                .isEqualTo("READY_FOR_IMPORT");
        assertThat(stageResponse.getSummary().getTotalTransactions()).isEqualTo(3);
        assertThat(stageResponse.getSummary().getValidTransactions()).isEqualTo(3);
        assertThat(stageResponse.getUnmappedCategories())
                .as("No unmapped categories - direct match worked")
                .isEmpty();

        // Verify category assignments via staging preview
        String stagingSessionId = stageResponse.getStagingSessionId();
        BankDataIngestionDto.GetStagingPreviewResponse preview = actor.getStagingPreview(cashFlowId, stagingSessionId);

        BankDataIngestionDto.StagedTransactionPreviewJson foodTxn = preview.getTransactions().stream()
                .filter(t -> t.getBankTransactionId().equals("TXN-001"))
                .findFirst()
                .orElseThrow();

        BankDataIngestionDto.StagedTransactionPreviewJson transportTxn = preview.getTransactions().stream()
                .filter(t -> t.getBankTransactionId().equals("TXN-002"))
                .findFirst()
                .orElseThrow();

        BankDataIngestionDto.StagedTransactionPreviewJson salaryTxn = preview.getTransactions().stream()
                .filter(t -> t.getBankTransactionId().equals("TXN-003"))
                .findFirst()
                .orElseThrow();

        // Verify direct category matches
        assertThat(foodTxn.getTargetCategory())
                .as("BankCategory 'Artykuły spożywcze' should directly match existing category")
                .isEqualTo("Artykuły spożywcze");

        assertThat(transportTxn.getTargetCategory())
                .as("BankCategory 'Transport' should directly match existing category")
                .isEqualTo("Transport");

        assertThat(salaryTxn.getTargetCategory())
                .as("BankCategory 'Wynagrodzenie' should directly match existing INFLOW category")
                .isEqualTo("Wynagrodzenie");

        log.info("Direct Category Match test passed:");
        log.info("  - TXN-001 (bankCategory='Artykuły spożywcze') -> {}", foodTxn.getTargetCategory());
        log.info("  - TXN-002 (bankCategory='Transport') -> {}", transportTxn.getTargetCategory());
        log.info("  - TXN-003 (bankCategory='Wynagrodzenie') -> {}", salaryTxn.getTargetCategory());
    }

    @Test
    @DisplayName("Direct match should have highest priority (before pattern matching)")
    void directMatchShouldHaveHighestPriority() {
        // given - create CashFlow with a category
        YearMonth startPeriod = YearMonth.of(2021, 7);

        String cashFlowId = actor.createCashFlowWithHistory(
                userId,
                uniqueCashFlowName(),
                startPeriod,
                Money.of(10000.0, "PLN")
        );

        // Create category matching the bankCategory
        actor.createCategory(cashFlowId, "Transport", Type.OUTFLOW);
        actor.createCategory(cashFlowId, "Taxi", Type.OUTFLOW); // Different category for pattern match

        // Create pattern mapping that would match "UBER" -> Taxi
        PatternMapping uberPattern = PatternMapping.createUser(
                "UBER",
                "Taxi",
                null,  // intendedParentCategory - not needed for this test
                Type.OUTFLOW,
                userId,
                cashFlowId,
                0.95
        );
        patternMappingRepository.save(uberPattern);

        log.info("Created categories: Transport, Taxi");
        log.info("Created pattern mapping: UBER -> Taxi");

        // when - stage transaction where:
        // - bankCategory = "Transport" (matches existing category)
        // - name contains "UBER" (matches pattern -> Taxi)
        // Direct match should win!
        BankDataIngestionDto.StageTransactionsResponse stageResponse = actor.stageTransactions(cashFlowId, List.of(
                actor.bankTransaction("TXN-001", "UBER TECHNOLOGIES",
                        "Transport", 50.0, "PLN", Type.OUTFLOW,
                        ZonedDateTime.of(2021, 8, 15, 10, 0, 0, 0, ZoneOffset.UTC))
        ));

        // then - should use direct match (Transport), not pattern match (Taxi)
        assertThat(stageResponse.getStatus()).isEqualTo("READY_FOR_IMPORT");

        BankDataIngestionDto.GetStagingPreviewResponse preview =
                actor.getStagingPreview(cashFlowId, stageResponse.getStagingSessionId());

        BankDataIngestionDto.StagedTransactionPreviewJson txn = preview.getTransactions().get(0);

        // KEY ASSERTION: Direct match wins over pattern matching
        assertThat(txn.getTargetCategory())
                .as("Direct match (Priority 0) should win: bankCategory 'Transport' beats pattern 'UBER' -> 'Taxi'")
                .isEqualTo("Transport");

        log.info("Priority test passed: {} -> {} (direct match beats pattern)",
                txn.getBankCategory(), txn.getTargetCategory());
    }

    @Test
    @DisplayName("Should work with nested categories (subcategories)")
    void shouldWorkWithNestedCategories() {
        // given - create CashFlow with nested category structure
        YearMonth startPeriod = YearMonth.of(2021, 7);

        String cashFlowId = actor.createCashFlowWithHistory(
                userId,
                uniqueCashFlowName(),
                startPeriod,
                Money.of(10000.0, "PLN")
        );

        // Create nested structure: Zakupy -> Artykuły spożywcze
        actor.createCategory(cashFlowId, "Zakupy", Type.OUTFLOW);
        actor.createCategory(cashFlowId, "Artykuły spożywcze", "Zakupy", Type.OUTFLOW);

        log.info("Created nested structure: Zakupy -> Artykuły spożywcze");

        // when - stage transaction with bankCategory matching subcategory
        BankDataIngestionDto.StageTransactionsResponse stageResponse = actor.stageTransactions(cashFlowId, List.of(
                actor.bankTransaction("TXN-001", "LIDL WARSZAWA",
                        "Artykuły spożywcze", 200.0, "PLN", Type.OUTFLOW,
                        ZonedDateTime.of(2021, 8, 15, 10, 0, 0, 0, ZoneOffset.UTC))
        ));

        // then - should match subcategory with correct parent
        assertThat(stageResponse.getStatus()).isEqualTo("READY_FOR_IMPORT");

        BankDataIngestionDto.GetStagingPreviewResponse preview =
                actor.getStagingPreview(cashFlowId, stageResponse.getStagingSessionId());

        BankDataIngestionDto.StagedTransactionPreviewJson txn = preview.getTransactions().get(0);

        assertThat(txn.getTargetCategory())
                .as("Should match subcategory 'Artykuły spożywcze'")
                .isEqualTo("Artykuły spożywcze");
        assertThat(txn.getParentCategory())
                .as("Parent category should be 'Zakupy'")
                .isEqualTo("Zakupy");

        log.info("Nested category test passed: {} -> {} (parent: {})",
                txn.getBankCategory(), txn.getTargetCategory(), txn.getParentCategory());
    }

    @Test
    @DisplayName("Should fall back to PENDING_MAPPING when bankCategory doesn't match any category")
    void shouldFallBackToPendingMappingWhenNoMatch() {
        // given - create CashFlow with categories that DON'T match bankCategory
        YearMonth startPeriod = YearMonth.of(2021, 7);

        String cashFlowId = actor.createCashFlowWithHistory(
                userId,
                uniqueCashFlowName(),
                startPeriod,
                Money.of(10000.0, "PLN")
        );

        // Create different categories (not matching incoming bankCategory)
        actor.createCategory(cashFlowId, "Inne wydatki", Type.OUTFLOW);

        log.info("Created category: Inne wydatki (doesn't match 'Artykuły spożywcze')");

        // when - stage transaction with bankCategory that doesn't match
        BankDataIngestionDto.StageTransactionsResponse stageResponse = actor.stageTransactions(cashFlowId, List.of(
                actor.bankTransaction("TXN-001", "BIEDRONKA WARSZAWA",
                        "Artykuły spożywcze", 150.0, "PLN", Type.OUTFLOW,
                        ZonedDateTime.of(2021, 8, 15, 10, 0, 0, 0, ZoneOffset.UTC))
        ));

        // then - should be HAS_UNMAPPED_CATEGORIES (no direct match, no pattern, no mapping)
        assertThat(stageResponse.getStatus())
                .as("Should be HAS_UNMAPPED_CATEGORIES when no direct match")
                .isEqualTo("HAS_UNMAPPED_CATEGORIES");
        assertThat(stageResponse.getUnmappedCategories())
                .extracting(BankDataIngestionDto.UnmappedCategoryJson::getBankCategory)
                .containsExactly("Artykuły spożywcze");

        log.info("Fallback test passed: bankCategory 'Artykuły spożywcze' is unmapped (no direct match)");
    }

    @Test
    @DisplayName("Should use direct match during revalidation")
    void shouldUseDirectMatchDuringRevalidation() {
        // given - create CashFlow WITHOUT matching category initially
        YearMonth startPeriod = YearMonth.of(2021, 7);

        String cashFlowId = actor.createCashFlowWithHistory(
                userId,
                uniqueCashFlowName(),
                startPeriod,
                Money.of(10000.0, "PLN")
        );

        // Initially no matching category
        log.info("Created CashFlow without matching category");

        // Stage transaction - should be PENDING_MAPPING
        BankDataIngestionDto.StageTransactionsResponse stageResponse = actor.stageTransactions(cashFlowId, List.of(
                actor.bankTransaction("TXN-001", "BIEDRONKA WARSZAWA",
                        "Artykuły spożywcze", 150.0, "PLN", Type.OUTFLOW,
                        ZonedDateTime.of(2021, 8, 15, 10, 0, 0, 0, ZoneOffset.UTC))
        ));

        assertThat(stageResponse.getStatus()).isEqualTo("HAS_UNMAPPED_CATEGORIES");
        String stagingSessionId = stageResponse.getStagingSessionId();

        // when - add matching category and revalidate
        actor.createCategory(cashFlowId, "Artykuły spożywcze", Type.OUTFLOW);
        log.info("Added category 'Artykuły spożywcze' to CashFlow");

        BankDataIngestionDto.RevalidateStagingResponse revalidateResponse =
                actor.revalidateStaging(cashFlowId, stagingSessionId);

        // then - transaction should now be valid via direct match
        assertThat(revalidateResponse.getStatus())
                .as("After adding matching category, revalidation should succeed")
                .isEqualTo("SUCCESS");
        assertThat(revalidateResponse.getSummary().getRevalidatedCount()).isEqualTo(1);
        assertThat(revalidateResponse.getSummary().getStillPendingCount()).isEqualTo(0);

        // Verify category assignment
        BankDataIngestionDto.GetStagingPreviewResponse preview =
                actor.getStagingPreview(cashFlowId, stagingSessionId);

        BankDataIngestionDto.StagedTransactionPreviewJson txn = preview.getTransactions().get(0);

        assertThat(txn.getTargetCategory())
                .as("Revalidation should use direct match")
                .isEqualTo("Artykuły spożywcze");
        assertThat(txn.getValidation().getStatus())
                .as("Transaction should be VALID after revalidation")
                .isEqualTo("VALID");

        log.info("Revalidation test passed: {} -> {} (direct match during revalidation)",
                txn.getBankCategory(), txn.getTargetCategory());
    }

    @Test
    @DisplayName("Should complete full import flow with direct category matching")
    void shouldCompleteFullImportFlowWithDirectMatching() {
        // given - create CashFlow with matching categories
        YearMonth startPeriod = YearMonth.of(2021, 7);

        String cashFlowId = actor.createCashFlowWithHistory(
                userId,
                uniqueCashFlowName(),
                startPeriod,
                Money.of(10000.0, "PLN")
        );

        actor.createCategory(cashFlowId, "Artykuły spożywcze", Type.OUTFLOW);

        // when - stage and import
        BankDataIngestionDto.StageTransactionsResponse stageResponse = actor.stageTransactions(cashFlowId, List.of(
                actor.bankTransaction("TXN-001", "BIEDRONKA",
                        "Artykuły spożywcze", 100.0, "PLN", Type.OUTFLOW,
                        ZonedDateTime.of(2021, 8, 15, 10, 0, 0, 0, ZoneOffset.UTC))
        ));

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
                .as("Imported transaction should be in 'Artykuły spożywcze' category (direct match)")
                .isEqualTo("Artykuły spożywcze");

        log.info("Full import with direct matching completed: {} -> category '{}'",
                importedTxn.getName(), importedTxn.getCategoryName());
    }

    @Test
    @DisplayName("Direct match should be case-insensitive")
    void directMatchShouldBeCaseInsensitive() {
        // given - create CashFlow with category in specific case
        YearMonth startPeriod = YearMonth.of(2021, 7);

        String cashFlowId = actor.createCashFlowWithHistory(
                userId,
                uniqueCashFlowName(),
                startPeriod,
                Money.of(10000.0, "PLN")
        );

        // Create categories with specific casing
        actor.createCategory(cashFlowId, "Artykuły spożywcze", Type.OUTFLOW);  // Mixed case
        actor.createCategory(cashFlowId, "Transport", Type.OUTFLOW);            // Title case
        actor.createCategory(cashFlowId, "Wynagrodzenie", Type.INFLOW);         // Title case

        log.info("Created categories with specific casing: 'Artykuły spożywcze', 'Transport', 'Wynagrodzenie'");

        // when - stage transactions with DIFFERENT casing in bankCategory
        BankDataIngestionDto.StageTransactionsResponse stageResponse = actor.stageTransactions(cashFlowId, List.of(
                // UPPERCASE bankCategory
                actor.bankTransaction("TXN-001", "BIEDRONKA",
                        "ARTYKUŁY SPOŻYWCZE", 100.0, "PLN", Type.OUTFLOW,
                        ZonedDateTime.of(2021, 8, 15, 10, 0, 0, 0, ZoneOffset.UTC)),
                // lowercase bankCategory
                actor.bankTransaction("TXN-002", "UBER",
                        "transport", 50.0, "PLN", Type.OUTFLOW,
                        ZonedDateTime.of(2021, 8, 16, 10, 0, 0, 0, ZoneOffset.UTC)),
                // Mixed case bankCategory (exact match)
                actor.bankTransaction("TXN-003", "PRZELEW PRZYCHODZĄCY",
                        "Wynagrodzenie", 8000.0, "PLN", Type.INFLOW,
                        ZonedDateTime.of(2021, 8, 10, 10, 0, 0, 0, ZoneOffset.UTC)),
                // Different case that should NOT match (nonexistent category)
                actor.bankTransaction("TXN-004", "NIEZNANY SKLEP",
                        "NIEZNANA KATEGORIA", 99.0, "PLN", Type.OUTFLOW,
                        ZonedDateTime.of(2021, 8, 17, 10, 0, 0, 0, ZoneOffset.UTC))
        ));

        // then - should match case-insensitively but preserve original category name
        assertThat(stageResponse.getStatus()).isEqualTo("HAS_UNMAPPED_CATEGORIES");
        assertThat(stageResponse.getSummary().getValidTransactions()).isEqualTo(3);
        assertThat(stageResponse.getUnmappedCategories()).hasSize(1);
        assertThat(stageResponse.getUnmappedCategories().get(0).getBankCategory())
                .isEqualTo("NIEZNANA KATEGORIA");

        BankDataIngestionDto.GetStagingPreviewResponse preview =
                actor.getStagingPreview(cashFlowId, stageResponse.getStagingSessionId());

        // Find transactions by name
        BankDataIngestionDto.StagedTransactionPreviewJson foodTxn = preview.getTransactions().stream()
                .filter(t -> t.getName().equals("BIEDRONKA")).findFirst().orElseThrow();
        BankDataIngestionDto.StagedTransactionPreviewJson transportTxn = preview.getTransactions().stream()
                .filter(t -> t.getName().equals("UBER")).findFirst().orElseThrow();
        BankDataIngestionDto.StagedTransactionPreviewJson salaryTxn = preview.getTransactions().stream()
                .filter(t -> t.getName().equals("PRZELEW PRZYCHODZĄCY")).findFirst().orElseThrow();
        BankDataIngestionDto.StagedTransactionPreviewJson unknownTxn = preview.getTransactions().stream()
                .filter(t -> t.getName().equals("NIEZNANY SKLEP")).findFirst().orElseThrow();

        // KEY ASSERTIONS: Case-insensitive matching preserves original category name
        assertThat(foodTxn.getTargetCategory())
                .as("UPPERCASE 'ARTYKUŁY SPOŻYWCZE' should match 'Artykuły spożywcze' (case-insensitive)")
                .isEqualTo("Artykuły spożywcze"); // Original case preserved
        assertThat(foodTxn.getValidation().getStatus()).isEqualTo("VALID");

        assertThat(transportTxn.getTargetCategory())
                .as("lowercase 'transport' should match 'Transport' (case-insensitive)")
                .isEqualTo("Transport"); // Original case preserved
        assertThat(transportTxn.getValidation().getStatus()).isEqualTo("VALID");

        assertThat(salaryTxn.getTargetCategory())
                .as("Exact case 'Wynagrodzenie' should match")
                .isEqualTo("Wynagrodzenie");
        assertThat(salaryTxn.getValidation().getStatus()).isEqualTo("VALID");

        assertThat(unknownTxn.getTargetCategory())
                .as("No matching category, should remain unmapped")
                .isNull();
        assertThat(unknownTxn.getValidation().getStatus()).isEqualTo("PENDING_MAPPING");

        log.info("Case-insensitive test passed:");
        log.info("  - bankCategory='ARTYKUŁY SPOŻYWCZE' -> targetCategory='{}' (UPPERCASE -> MixedCase)", foodTxn.getTargetCategory());
        log.info("  - bankCategory='transport' -> targetCategory='{}' (lowercase -> TitleCase)", transportTxn.getTargetCategory());
        log.info("  - bankCategory='Wynagrodzenie' -> targetCategory='{}' (exact match)", salaryTxn.getTargetCategory());
        log.info("  - bankCategory='NIEZNANA KATEGORIA' -> PENDING_MAPPING (no match)");
    }
}
