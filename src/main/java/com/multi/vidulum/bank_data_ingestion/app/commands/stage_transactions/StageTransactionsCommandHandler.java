package com.multi.vidulum.bank_data_ingestion.app.commands.stage_transactions;

import com.multi.vidulum.bank_data_ingestion.app.BankDataIngestionConfig;
import com.multi.vidulum.bank_data_ingestion.app.CashFlowInfo;
import com.multi.vidulum.bank_data_ingestion.app.CashFlowServiceClient;
import com.multi.vidulum.bank_data_ingestion.domain.*;
import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.CashFlowId;
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

@Slf4j
@Component
@AllArgsConstructor
public class StageTransactionsCommandHandler
        implements CommandHandler<StageTransactionsCommand, StageTransactionsResult> {

    private final StagedTransactionRepository stagedTransactionRepository;
    private final CategoryMappingRepository categoryMappingRepository;
    private final CashFlowServiceClient cashFlowServiceClient;
    private final BankDataIngestionConfig config;
    private final Clock clock;

    @Override
    public StageTransactionsResult handle(StageTransactionsCommand command) {
        // Load CashFlow info via service client
        CashFlowInfo cashFlowInfo;
        try {
            cashFlowInfo = cashFlowServiceClient.getCashFlowInfo(command.cashFlowId().id());
        } catch (CashFlowServiceClient.CashFlowNotFoundException e) {
            throw new CashFlowDoesNotExistsException(command.cashFlowId());
        }

        ZonedDateTime now = ZonedDateTime.now(clock);
        StagingSessionId stagingSessionId = StagingSessionId.generate();

        // Load all mappings for this CashFlow
        List<CategoryMapping> mappings = categoryMappingRepository.findByCashFlowId(command.cashFlowId());
        Map<MappingKey, CategoryMapping> mappingMap = buildMappingMap(mappings);

        // Find unmapped categories first
        List<StageTransactionsResult.UnmappedCategory> unmappedCategories = findUnmappedCategories(
                command.transactions(), mappingMap);

        if (!unmappedCategories.isEmpty()) {
            log.warn("Found {} unmapped categories for CashFlow [{}]",
                    unmappedCategories.size(), command.cashFlowId().id());
            return createUnmappedCategoriesResult(
                    stagingSessionId, command.cashFlowId(), now, unmappedCategories);
        }

        // Process and stage transactions
        List<StagedTransaction> stagedTransactions = new ArrayList<>();
        Set<String> existingBankTransactionIds = cashFlowInfo.existingTransactionIds();

        for (StageTransactionsCommand.BankTransaction txn : command.transactions()) {
            StagedTransaction staged = processTransaction(
                    txn, command.cashFlowId(), stagingSessionId, cashFlowInfo,
                    mappingMap, existingBankTransactionIds, now);
            stagedTransactions.add(staged);
        }

        // Save all staged transactions
        stagedTransactionRepository.saveAll(stagedTransactions);

        log.info("Staged {} transactions for CashFlow [{}] in session [{}]",
                stagedTransactions.size(), command.cashFlowId().id(), stagingSessionId.id());

        // Build and return result
        return buildResult(stagingSessionId, command.cashFlowId(), stagedTransactions,
                cashFlowInfo, mappingMap, now);
    }

    private Map<MappingKey, CategoryMapping> buildMappingMap(List<CategoryMapping> mappings) {
        Map<MappingKey, CategoryMapping> map = new HashMap<>();
        for (CategoryMapping mapping : mappings) {
            map.put(new MappingKey(mapping.bankCategoryName(), mapping.categoryType()), mapping);
        }
        return map;
    }

    private record MappingKey(String bankCategory, Type type) {
    }

    private List<StageTransactionsResult.UnmappedCategory> findUnmappedCategories(
            List<StageTransactionsCommand.BankTransaction> transactions,
            Map<MappingKey, CategoryMapping> mappingMap) {

        Map<MappingKey, Integer> unmappedCounts = new HashMap<>();

        for (StageTransactionsCommand.BankTransaction txn : transactions) {
            MappingKey key = new MappingKey(txn.bankCategory(), txn.type());
            if (!mappingMap.containsKey(key)) {
                unmappedCounts.merge(key, 1, Integer::sum);
            }
        }

        return unmappedCounts.entrySet().stream()
                .map(e -> new StageTransactionsResult.UnmappedCategory(
                        e.getKey().bankCategory(),
                        e.getValue(),
                        e.getKey().type()))
                .toList();
    }

    private StagedTransaction processTransaction(
            StageTransactionsCommand.BankTransaction txn,
            CashFlowId cashFlowId,
            StagingSessionId stagingSessionId,
            CashFlowInfo cashFlowInfo,
            Map<MappingKey, CategoryMapping> mappingMap,
            Set<String> existingBankTransactionIds,
            ZonedDateTime now) {

        OriginalTransactionData originalData = new OriginalTransactionData(
                txn.bankTransactionId(),
                txn.name(),
                txn.description(),
                txn.bankCategory(),
                txn.money(),
                txn.type(),
                txn.paidDate()
        );

        // Get mapping
        MappingKey key = new MappingKey(txn.bankCategory(), txn.type());
        CategoryMapping mapping = mappingMap.get(key);

        MappedTransactionData mappedData = new MappedTransactionData(
                txn.name(),
                txn.description(),
                mapping.targetCategoryName(),
                mapping.parentCategoryName(),
                txn.money(),
                txn.type(),
                txn.paidDate()
        );

        // Validate transaction
        TransactionValidation validation = validateTransaction(txn, cashFlowInfo, existingBankTransactionIds, now);

        return StagedTransaction.create(
                cashFlowId,
                stagingSessionId,
                originalData,
                mappedData,
                validation,
                now,
                config.getStagingTtlHours()
        );
    }

    private TransactionValidation validateTransaction(
            StageTransactionsCommand.BankTransaction txn,
            CashFlowInfo cashFlowInfo,
            Set<String> existingBankTransactionIds,
            ZonedDateTime now) {

        List<String> errors = new ArrayList<>();

        // Check for duplicate
        if (existingBankTransactionIds.contains(txn.bankTransactionId())) {
            return TransactionValidation.duplicate(txn.bankTransactionId());
        }

        // CashFlow must be in SETUP mode for historical import
        if (!cashFlowInfo.isInSetupMode()) {
            errors.add("CashFlow is not in SETUP mode");
        }

        // paidDate validation for historical import
        YearMonth paidPeriod = YearMonth.from(txn.paidDate());
        YearMonth activePeriod = cashFlowInfo.activePeriod();
        YearMonth startPeriod = cashFlowInfo.startPeriod();

        if (!paidPeriod.isBefore(activePeriod)) {
            errors.add(String.format("paidDate %s is not before activePeriod %s",
                    paidPeriod, activePeriod));
        }

        if (paidPeriod.isBefore(startPeriod)) {
            errors.add(String.format("paidDate %s is before startPeriod %s",
                    paidPeriod, startPeriod));
        }

        if (txn.paidDate().isAfter(now)) {
            errors.add("paidDate cannot be in the future");
        }

        if (!errors.isEmpty()) {
            return TransactionValidation.invalid(errors);
        }

        return TransactionValidation.valid();
    }

    private StageTransactionsResult createUnmappedCategoriesResult(
            StagingSessionId stagingSessionId,
            CashFlowId cashFlowId,
            ZonedDateTime now,
            List<StageTransactionsResult.UnmappedCategory> unmappedCategories) {

        return new StageTransactionsResult(
                stagingSessionId,
                cashFlowId,
                StageTransactionsResult.StagingStatus.HAS_UNMAPPED_CATEGORIES,
                now.plusHours(config.getStagingTtlHours()),
                new StageTransactionsResult.StagingSummary(0, 0, 0, 0),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                unmappedCategories
        );
    }

    private StageTransactionsResult buildResult(
            StagingSessionId stagingSessionId,
            CashFlowId cashFlowId,
            List<StagedTransaction> stagedTransactions,
            CashFlowInfo cashFlowInfo,
            Map<MappingKey, CategoryMapping> mappingMap,
            ZonedDateTime now) {

        // Calculate summary
        int total = stagedTransactions.size();
        int valid = (int) stagedTransactions.stream().filter(StagedTransaction::isValid).count();
        int invalid = (int) stagedTransactions.stream().filter(StagedTransaction::isInvalid).count();
        int duplicates = (int) stagedTransactions.stream().filter(StagedTransaction::isDuplicate).count();

        StageTransactionsResult.StagingSummary summary =
                new StageTransactionsResult.StagingSummary(total, valid, invalid, duplicates);

        // Build category breakdown
        List<StageTransactionsResult.CategoryBreakdown> categoryBreakdown =
                buildCategoryBreakdown(stagedTransactions, cashFlowInfo, mappingMap);

        // Build categories to create
        List<StageTransactionsResult.CategoryToCreate> categoriesToCreate =
                buildCategoriesToCreate(stagedTransactions, cashFlowInfo, mappingMap);

        // Build monthly breakdown
        List<StageTransactionsResult.MonthlyBreakdown> monthlyBreakdown =
                buildMonthlyBreakdown(stagedTransactions);

        // Build duplicates list
        List<StageTransactionsResult.DuplicateInfo> duplicateInfos = stagedTransactions.stream()
                .filter(StagedTransaction::isDuplicate)
                .map(st -> new StageTransactionsResult.DuplicateInfo(
                        st.originalData().bankTransactionId(),
                        st.originalData().name(),
                        st.validation().duplicateOf()))
                .toList();

        // Determine status
        StageTransactionsResult.StagingStatus status;
        if (invalid > 0) {
            status = StageTransactionsResult.StagingStatus.HAS_VALIDATION_ERRORS;
        } else {
            status = StageTransactionsResult.StagingStatus.READY_FOR_IMPORT;
        }

        return new StageTransactionsResult(
                stagingSessionId,
                cashFlowId,
                status,
                now.plusHours(config.getStagingTtlHours()),
                summary,
                categoryBreakdown,
                categoriesToCreate,
                monthlyBreakdown,
                duplicateInfos,
                List.of()
        );
    }

    private List<StageTransactionsResult.CategoryBreakdown> buildCategoryBreakdown(
            List<StagedTransaction> stagedTransactions,
            CashFlowInfo cashFlowInfo,
            Map<MappingKey, CategoryMapping> mappingMap) {

        Map<String, CategoryBreakdownBuilder> builderMap = new HashMap<>();

        for (StagedTransaction st : stagedTransactions) {
            if (!st.isValid()) continue;

            String categoryKey = st.mappedData().categoryName().name() + ":" + st.mappedData().type();
            CategoryBreakdownBuilder builder = builderMap.computeIfAbsent(categoryKey,
                    k -> new CategoryBreakdownBuilder(
                            st.mappedData().categoryName().name(),
                            st.mappedData().parentCategoryName() != null ?
                                    st.mappedData().parentCategoryName().name() : null,
                            st.mappedData().type(),
                            st.mappedData().money().getCurrency()
                    ));

            builder.addTransaction(st.mappedData().money());
        }

        Set<String> existingCategories = cashFlowInfo.getAllCategoryNames();

        return builderMap.values().stream()
                .map(b -> b.build(existingCategories, mappingMap))
                .toList();
    }

    private static class CategoryBreakdownBuilder {
        private final String categoryName;
        private final String parentCategory;
        private final Type type;
        private final String currency;
        private int count = 0;
        private BigDecimal totalAmount = BigDecimal.ZERO;

        CategoryBreakdownBuilder(String categoryName, String parentCategory, Type type, String currency) {
            this.categoryName = categoryName;
            this.parentCategory = parentCategory;
            this.type = type;
            this.currency = currency;
        }

        void addTransaction(Money money) {
            count++;
            totalAmount = totalAmount.add(money.getAmount());
        }

        StageTransactionsResult.CategoryBreakdown build(
                Set<String> existingCategories,
                Map<MappingKey, CategoryMapping> mappingMap) {

            boolean isNewCategory = !existingCategories.contains(categoryName);

            return new StageTransactionsResult.CategoryBreakdown(
                    categoryName,
                    parentCategory,
                    count,
                    Money.of(totalAmount.doubleValue(), currency),
                    type,
                    isNewCategory
            );
        }
    }

    private List<StageTransactionsResult.CategoryToCreate> buildCategoriesToCreate(
            List<StagedTransaction> stagedTransactions,
            CashFlowInfo cashFlowInfo,
            Map<MappingKey, CategoryMapping> mappingMap) {

        Set<String> existingCategories = cashFlowInfo.getAllCategoryNames();
        Set<String> newCategoriesAdded = new HashSet<>();
        List<StageTransactionsResult.CategoryToCreate> result = new ArrayList<>();

        for (StagedTransaction st : stagedTransactions) {
            if (!st.isValid()) continue;

            String categoryName = st.mappedData().categoryName().name();
            String categoryKey = categoryName + ":" + st.mappedData().type();

            if (!existingCategories.contains(categoryName) && !newCategoriesAdded.contains(categoryKey)) {
                MappingKey mappingKey = new MappingKey(st.originalData().bankCategory(), st.mappedData().type());
                CategoryMapping mapping = mappingMap.get(mappingKey);

                if (mapping != null && (mapping.action() == MappingAction.CREATE_NEW ||
                        mapping.action() == MappingAction.CREATE_SUBCATEGORY)) {

                    result.add(new StageTransactionsResult.CategoryToCreate(
                            categoryName,
                            st.mappedData().parentCategoryName() != null ?
                                    st.mappedData().parentCategoryName().name() : null,
                            st.mappedData().type()
                    ));
                    newCategoriesAdded.add(categoryKey);
                }
            }
        }

        return result;
    }

    private List<StageTransactionsResult.MonthlyBreakdown> buildMonthlyBreakdown(
            List<StagedTransaction> stagedTransactions) {

        Map<YearMonth, MonthlyBreakdownBuilder> builderMap = new TreeMap<>();

        for (StagedTransaction st : stagedTransactions) {
            if (!st.isValid()) continue;

            YearMonth month = YearMonth.from(st.mappedData().paidDate());
            String currency = st.mappedData().money().getCurrency();

            MonthlyBreakdownBuilder builder = builderMap.computeIfAbsent(month,
                    k -> new MonthlyBreakdownBuilder(month, currency));
            builder.addTransaction(st.mappedData().money(), st.mappedData().type());
        }

        return builderMap.values().stream()
                .map(MonthlyBreakdownBuilder::build)
                .toList();
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

        StageTransactionsResult.MonthlyBreakdown build() {
            return new StageTransactionsResult.MonthlyBreakdown(
                    month.toString(),
                    Money.of(inflowTotal.doubleValue(), currency),
                    Money.of(outflowTotal.doubleValue(), currency),
                    count
            );
        }
    }
}
