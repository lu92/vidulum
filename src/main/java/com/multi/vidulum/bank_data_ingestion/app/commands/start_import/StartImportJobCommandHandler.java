package com.multi.vidulum.bank_data_ingestion.app.commands.start_import;

import com.multi.vidulum.bank_data_ingestion.app.BankDataIngestionConfig;
import com.multi.vidulum.bank_data_ingestion.app.CashFlowInfo;
import com.multi.vidulum.bank_data_ingestion.app.CashFlowServiceClient;
import com.multi.vidulum.bank_data_ingestion.domain.*;
import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Handler for starting an import job.
 * This processes staged transactions and imports them into the CashFlow.
 * <p>
 * The import happens synchronously in two phases:
 * 1. CREATING_CATEGORIES - creates any new categories needed
 * 2. IMPORTING_TRANSACTIONS - imports each valid transaction
 */
@Slf4j
@Component
@AllArgsConstructor
public class StartImportJobCommandHandler implements CommandHandler<StartImportJobCommand, StartImportJobResult> {

    private final ImportJobRepository importJobRepository;
    private final StagedTransactionRepository stagedTransactionRepository;
    private final CategoryMappingRepository categoryMappingRepository;
    private final CashFlowServiceClient cashFlowServiceClient;
    private final BankDataIngestionConfig config;
    private final Clock clock;

    @Override
    public StartImportJobResult handle(StartImportJobCommand command) {
        ZonedDateTime now = ZonedDateTime.now(clock);

        // Validate: CashFlow must exist and be in SETUP mode
        CashFlowInfo cashFlowInfo;
        try {
            cashFlowInfo = cashFlowServiceClient.getCashFlowInfo(command.cashFlowId().id());
        } catch (CashFlowServiceClient.CashFlowNotFoundException e) {
            throw new CashFlowDoesNotExistsException(command.cashFlowId());
        }

        if (!cashFlowInfo.isInSetupMode()) {
            throw new StagingSessionNotReadyException(
                    command.stagingSessionId(),
                    "CashFlow is not in SETUP mode. Current status: " + cashFlowInfo.status()
            );
        }

        // Validate: No active import job for this staging session
        if (importJobRepository.existsActiveByStagingSessionId(command.stagingSessionId())) {
            throw new ImportJobAlreadyExistsException(command.stagingSessionId());
        }

        // Load staged transactions
        List<StagedTransaction> stagedTransactions = stagedTransactionRepository
                .findByStagingSessionId(command.stagingSessionId());

        if (stagedTransactions.isEmpty()) {
            throw new StagingSessionNotFoundException(command.stagingSessionId());
        }

        // Check if any transactions are expired
        StagedTransaction firstTransaction = stagedTransactions.get(0);
        if (firstTransaction.expiresAt().isBefore(now)) {
            throw new StagingSessionNotReadyException(
                    command.stagingSessionId(),
                    "Staging session has expired"
            );
        }

        // Calculate input statistics
        List<StagedTransaction> validTransactions = stagedTransactions.stream()
                .filter(StagedTransaction::isValid)
                .toList();

        int totalTransactions = stagedTransactions.size();
        int validCount = validTransactions.size();
        int duplicateCount = (int) stagedTransactions.stream().filter(StagedTransaction::isDuplicate).count();

        // Determine categories to create
        List<CategoryMapping> mappings = categoryMappingRepository.findByCashFlowId(command.cashFlowId());
        List<CategoryToCreate> categoriesToCreate = determineCategoriesToCreate(
                validTransactions, cashFlowInfo, mappings);

        // Create the import job
        ImportJob importJob = ImportJob.create(
                command.cashFlowId(),
                command.stagingSessionId(),
                totalTransactions,
                validCount,
                duplicateCount,
                categoriesToCreate.size(),
                now
        );

        // Save initial job state
        importJob = importJobRepository.save(importJob);
        log.info("Created import job [{}] for CashFlow [{}] with {} valid transactions and {} categories to create",
                importJob.jobId().id(), command.cashFlowId().id(), validCount, categoriesToCreate.size());

        // Start processing
        importJob = importJob.startProcessing(now);
        importJob = importJobRepository.save(importJob);

        try {
            // Phase 1: Create categories
            importJob = processCreateCategoriesPhase(importJob, categoriesToCreate, now);

            // Phase 2: Import transactions
            importJob = processImportTransactionsPhase(importJob, validTransactions, now);

            // Build summary
            ImportJob.ImportSummary summary = buildImportSummary(importJob, validTransactions, now);

            // Complete the job
            importJob = importJob.complete(summary, ZonedDateTime.now(clock), config.getRollbackWindowHours());
            importJob = importJobRepository.save(importJob);

            log.info("Import job [{}] completed successfully. Imported {} transactions, created {} categories",
                    importJob.jobId().id(),
                    importJob.result().transactionsImported(),
                    importJob.result().categoriesCreated().size());

        } catch (Exception e) {
            log.error("Import job [{}] failed: {}", importJob.jobId().id(), e.getMessage(), e);
            importJob = importJob.fail(e.getMessage(), ZonedDateTime.now(clock));
            importJob = importJobRepository.save(importJob);
        }

        String baseUrl = "/api/v1/bank-data-ingestion/" + command.cashFlowId().id();
        return StartImportJobResult.from(importJob, baseUrl);
    }

    /**
     * Determine which categories need to be created based on mappings and existing categories.
     */
    private List<CategoryToCreate> determineCategoriesToCreate(
            List<StagedTransaction> validTransactions,
            CashFlowInfo cashFlowInfo,
            List<CategoryMapping> mappings) {

        Set<String> existingCategories = cashFlowInfo.getAllCategoryNames();
        Map<String, CategoryMapping> mappingByBankCategoryAndType = buildMappingMap(mappings);
        Set<String> categoriesToCreateSet = new HashSet<>();
        List<CategoryToCreate> result = new ArrayList<>();

        for (StagedTransaction st : validTransactions) {
            String categoryKey = st.mappedData().categoryName().name() + ":" + st.mappedData().type();

            if (!existingCategories.contains(st.mappedData().categoryName().name()) &&
                    !categoriesToCreateSet.contains(categoryKey)) {

                String mappingKey = st.originalData().bankCategory() + ":" + st.mappedData().type();
                CategoryMapping mapping = mappingByBankCategoryAndType.get(mappingKey);

                if (mapping != null && (mapping.action() == MappingAction.CREATE_NEW ||
                        mapping.action() == MappingAction.CREATE_SUBCATEGORY)) {

                    result.add(new CategoryToCreate(
                            st.mappedData().categoryName().name(),
                            st.mappedData().parentCategoryName() != null
                                    ? st.mappedData().parentCategoryName().name() : null,
                            st.mappedData().type()
                    ));
                    categoriesToCreateSet.add(categoryKey);
                }
            }
        }

        return result;
    }

    private record CategoryToCreate(String name, String parent, Type type) {
    }

    private Map<String, CategoryMapping> buildMappingMap(List<CategoryMapping> mappings) {
        Map<String, CategoryMapping> map = new HashMap<>();
        for (CategoryMapping mapping : mappings) {
            String key = mapping.bankCategoryName() + ":" + mapping.categoryType();
            map.put(key, mapping);
        }
        return map;
    }

    /**
     * Process Phase 1: Create categories.
     */
    private ImportJob processCreateCategoriesPhase(
            ImportJob importJob,
            List<CategoryToCreate> categoriesToCreate,
            ZonedDateTime startTime) {

        int processed = 0;
        int updateInterval = config.getProgressUpdateInterval();

        for (CategoryToCreate cat : categoriesToCreate) {
            try {
                cashFlowServiceClient.createCategory(
                        importJob.cashFlowId().id(),
                        cat.name(),
                        cat.parent(),
                        cat.type()
                );

                importJob = importJob.recordCreatedCategory(cat.name());
                processed++;

                if (processed % updateInterval == 0 || processed == categoriesToCreate.size()) {
                    importJob = importJob.updateProgress(ImportPhase.CREATING_CATEGORIES, processed);
                    importJob = importJobRepository.save(importJob);
                }

                log.debug("Created category [{}] ({}/{})", cat.name(), processed, categoriesToCreate.size());

            } catch (CashFlowServiceClient.CategoryAlreadyExistsException e) {
                // Category already exists - not an error, just skip
                log.debug("Category [{}] already exists, skipping", cat.name());
                processed++;
            } catch (Exception e) {
                log.warn("Failed to create category [{}]: {}", cat.name(), e.getMessage());
                // Continue with other categories
                processed++;
            }
        }

        // Complete the phase
        importJob = importJob.completePhase(ImportPhase.CREATING_CATEGORIES, ZonedDateTime.now(clock));
        importJob = importJobRepository.save(importJob);

        return importJob;
    }

    /**
     * Process Phase 2: Import transactions.
     */
    private ImportJob processImportTransactionsPhase(
            ImportJob importJob,
            List<StagedTransaction> validTransactions,
            ZonedDateTime startTime) {

        int processed = 0;
        int updateInterval = config.getProgressUpdateInterval();

        for (StagedTransaction st : validTransactions) {
            try {
                CashFlowServiceClient.ImportTransactionRequest request =
                        new CashFlowServiceClient.ImportTransactionRequest(
                                st.mappedData().categoryName().name(),
                                st.mappedData().name(),
                                st.mappedData().description(),
                                st.mappedData().money().getAmount().doubleValue(),
                                st.mappedData().money().getCurrency(),
                                st.mappedData().type(),
                                st.mappedData().paidDate().toLocalDate(),  // dueDate
                                st.mappedData().paidDate().toLocalDate()   // paidDate
                        );

                String cashChangeId = cashFlowServiceClient.importHistoricalTransaction(
                        importJob.cashFlowId().id(), request);

                importJob = importJob.recordCreatedCashChange(cashChangeId);
                processed++;

                if (processed % updateInterval == 0 || processed == validTransactions.size()) {
                    importJob = importJob.updateProgress(ImportPhase.IMPORTING_TRANSACTIONS, processed);
                    importJob = importJobRepository.save(importJob);
                }

                log.debug("Imported transaction [{}] ({}/{})",
                        st.originalData().bankTransactionId(), processed, validTransactions.size());

            } catch (Exception e) {
                log.warn("Failed to import transaction [{}]: {}",
                        st.originalData().bankTransactionId(), e.getMessage());

                importJob = importJob.recordFailedTransaction(
                        st.originalData().bankTransactionId(),
                        e.getMessage()
                );
                processed++;
            }
        }

        return importJob;
    }

    /**
     * Build the import summary with category and monthly breakdowns.
     */
    private ImportJob.ImportSummary buildImportSummary(
            ImportJob importJob,
            List<StagedTransaction> validTransactions,
            ZonedDateTime startTime) {

        // Category breakdown
        Map<String, CategoryBreakdownBuilder> categoryBuilders = new HashMap<>();
        Set<String> createdCategories = new HashSet<>(importJob.result().categoriesCreated());

        for (StagedTransaction st : validTransactions) {
            String categoryKey = st.mappedData().categoryName().name() + ":" + st.mappedData().type();
            CategoryBreakdownBuilder builder = categoryBuilders.computeIfAbsent(categoryKey,
                    k -> new CategoryBreakdownBuilder(
                            st.mappedData().categoryName().name(),
                            st.mappedData().parentCategoryName() != null
                                    ? st.mappedData().parentCategoryName().name() : null,
                            st.mappedData().type(),
                            st.mappedData().money().getCurrency(),
                            createdCategories.contains(st.mappedData().categoryName().name())
                    ));
            builder.addTransaction(st.mappedData().money());
        }

        List<ImportJob.CategoryBreakdown> categoryBreakdown = categoryBuilders.values().stream()
                .map(CategoryBreakdownBuilder::build)
                .toList();

        // Monthly breakdown
        Map<YearMonth, MonthlyBreakdownBuilder> monthlyBuilders = new TreeMap<>();

        for (StagedTransaction st : validTransactions) {
            YearMonth month = YearMonth.from(st.mappedData().paidDate());
            String currency = st.mappedData().money().getCurrency();

            MonthlyBreakdownBuilder builder = monthlyBuilders.computeIfAbsent(month,
                    k -> new MonthlyBreakdownBuilder(month, currency));
            builder.addTransaction(st.mappedData().money(), st.mappedData().type());
        }

        List<ImportJob.MonthlyBreakdown> monthlyBreakdown = monthlyBuilders.values().stream()
                .map(MonthlyBreakdownBuilder::build)
                .toList();

        long totalDurationMs = java.time.Duration.between(startTime, ZonedDateTime.now(clock)).toMillis();

        return ImportJob.ImportSummary.create(categoryBreakdown, monthlyBreakdown, totalDurationMs);
    }

    // ============ Builder Classes ============

    private static class CategoryBreakdownBuilder {
        private final String categoryName;
        private final String parentCategory;
        private final Type type;
        private final String currency;
        private final boolean isNewCategory;
        private int count = 0;
        private BigDecimal totalAmount = BigDecimal.ZERO;

        CategoryBreakdownBuilder(String categoryName, String parentCategory, Type type,
                                 String currency, boolean isNewCategory) {
            this.categoryName = categoryName;
            this.parentCategory = parentCategory;
            this.type = type;
            this.currency = currency;
            this.isNewCategory = isNewCategory;
        }

        void addTransaction(Money money) {
            count++;
            totalAmount = totalAmount.add(money.getAmount());
        }

        ImportJob.CategoryBreakdown build() {
            return new ImportJob.CategoryBreakdown(
                    categoryName,
                    parentCategory,
                    count,
                    Money.of(totalAmount.doubleValue(), currency),
                    type,
                    isNewCategory
            );
        }
    }

    private static class MonthlyBreakdownBuilder {
        private final YearMonth month;
        private final String currency;
        private BigDecimal inflowTotal = BigDecimal.ZERO;
        private BigDecimal outflowTotal = BigDecimal.ZERO;
        private int count = 0;

        MonthlyBreakdownBuilder(YearMonth month, String currency) {
            this.month = month;
            this.currency = currency;
        }

        void addTransaction(Money money, Type type) {
            count++;
            if (type == Type.INFLOW) {
                inflowTotal = inflowTotal.add(money.getAmount());
            } else {
                outflowTotal = outflowTotal.add(money.getAmount());
            }
        }

        ImportJob.MonthlyBreakdown build() {
            return new ImportJob.MonthlyBreakdown(
                    month.toString(),
                    Money.of(inflowTotal.doubleValue(), currency),
                    Money.of(outflowTotal.doubleValue(), currency),
                    count
            );
        }
    }
}
