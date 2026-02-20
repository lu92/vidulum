package com.multi.vidulum.bank_data_ingestion.app.queries.get_staging_preview;

import com.multi.vidulum.bank_data_ingestion.domain.*;
import com.multi.vidulum.cashflow.domain.*;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
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
public class GetStagingPreviewQueryHandler
        implements QueryHandler<GetStagingPreviewQuery, GetStagingPreviewResult> {

    private final StagedTransactionRepository stagedTransactionRepository;
    private final CategoryMappingRepository categoryMappingRepository;
    private final DomainCashFlowRepository domainCashFlowRepository;
    private final Clock clock;

    @Override
    public GetStagingPreviewResult query(GetStagingPreviewQuery query) {
        List<StagedTransaction> stagedTransactions =
                stagedTransactionRepository.findByStagingSessionId(query.stagingSessionId());

        if (stagedTransactions.isEmpty()) {
            log.warn("No staged transactions found for session [{}]", query.stagingSessionId().id());
            throw new StagingSessionNotFoundException(query.stagingSessionId());
        }

        // Check if session has expired (based on first transaction)
        ZonedDateTime expiresAt = stagedTransactions.get(0).expiresAt();
        ZonedDateTime now = ZonedDateTime.now(clock);
        if (expiresAt.isBefore(now)) {
            log.warn("Staging session [{}] has expired", query.stagingSessionId().id());
            return createExpiredResult(query, expiresAt);
        }

        // Load CashFlow for category breakdown
        CashFlow cashFlow = domainCashFlowRepository.findById(query.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(query.cashFlowId()));

        CashFlowSnapshot snapshot = cashFlow.getSnapshot();
        List<CategoryMapping> mappings = categoryMappingRepository.findByCashFlowId(query.cashFlowId());
        Map<MappingKey, CategoryMapping> mappingMap = buildMappingMap(mappings);

        return buildResult(query, stagedTransactions, snapshot, mappingMap, expiresAt);
    }

    private GetStagingPreviewResult createExpiredResult(GetStagingPreviewQuery query, ZonedDateTime expiresAt) {
        return new GetStagingPreviewResult(
                query.stagingSessionId(),
                query.cashFlowId(),
                GetStagingPreviewResult.StagingStatus.EXPIRED,
                expiresAt,
                new GetStagingPreviewResult.StagingSummary(0, 0, 0, 0),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private GetStagingPreviewResult buildResult(
            GetStagingPreviewQuery query,
            List<StagedTransaction> stagedTransactions,
            CashFlowSnapshot snapshot,
            Map<MappingKey, CategoryMapping> mappingMap,
            ZonedDateTime expiresAt) {

        // Calculate summary
        int total = stagedTransactions.size();
        int valid = (int) stagedTransactions.stream().filter(StagedTransaction::isValid).count();
        int invalid = (int) stagedTransactions.stream().filter(StagedTransaction::isInvalid).count();
        int duplicates = (int) stagedTransactions.stream().filter(StagedTransaction::isDuplicate).count();
        int pendingMapping = (int) stagedTransactions.stream().filter(StagedTransaction::isPendingMapping).count();

        GetStagingPreviewResult.StagingSummary summary =
                new GetStagingPreviewResult.StagingSummary(total, valid, invalid, duplicates);

        // Build transaction previews (all transactions, including invalid ones)
        List<GetStagingPreviewResult.StagedTransactionPreview> transactionPreviews =
                stagedTransactions.stream()
                        .map(this::toTransactionPreview)
                        .toList();

        // Build category breakdown (only for valid transactions)
        List<GetStagingPreviewResult.CategoryBreakdown> categoryBreakdown =
                buildCategoryBreakdown(stagedTransactions, snapshot);

        // Build categories to create
        List<GetStagingPreviewResult.CategoryToCreate> categoriesToCreate =
                buildCategoriesToCreate(stagedTransactions, snapshot, mappingMap);

        // Build monthly breakdown (only for valid transactions)
        List<GetStagingPreviewResult.MonthlyBreakdown> monthlyBreakdown =
                buildMonthlyBreakdown(stagedTransactions);

        // Determine status
        GetStagingPreviewResult.StagingStatus status;
        if (pendingMapping > 0) {
            status = GetStagingPreviewResult.StagingStatus.HAS_UNMAPPED_CATEGORIES;
        } else if (invalid > 0) {
            status = GetStagingPreviewResult.StagingStatus.HAS_VALIDATION_ERRORS;
        } else {
            status = GetStagingPreviewResult.StagingStatus.READY_FOR_IMPORT;
        }

        return new GetStagingPreviewResult(
                query.stagingSessionId(),
                query.cashFlowId(),
                status,
                expiresAt,
                summary,
                transactionPreviews,
                categoryBreakdown,
                categoriesToCreate,
                monthlyBreakdown
        );
    }

    private GetStagingPreviewResult.StagedTransactionPreview toTransactionPreview(StagedTransaction st) {
        // For invalid transactions, mappedData may be null - use originalData as fallback
        boolean hasMappedData = st.mappedData() != null;

        return new GetStagingPreviewResult.StagedTransactionPreview(
                st.stagedTransactionId().id(),
                st.originalData().bankTransactionId(),
                st.originalData().name(),
                st.originalData().description(),
                st.originalData().bankCategory(),
                hasMappedData ? st.mappedData().categoryName().name() : null,
                hasMappedData && st.mappedData().parentCategoryName() != null
                        ? st.mappedData().parentCategoryName().name() : null,
                hasMappedData ? st.mappedData().money() : st.originalData().money(),
                hasMappedData ? st.mappedData().type() : st.originalData().type(),
                hasMappedData ? st.mappedData().paidDate() : st.originalData().paidDate(),
                new GetStagingPreviewResult.ValidationResult(
                        st.validation().status().name(),
                        st.validation().errors(),
                        st.validation().duplicateOf()
                )
        );
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

    private List<GetStagingPreviewResult.CategoryBreakdown> buildCategoryBreakdown(
            List<StagedTransaction> stagedTransactions,
            CashFlowSnapshot snapshot) {

        Map<String, CategoryBreakdownBuilder> builderMap = new HashMap<>();
        Set<String> existingCategories = getExistingCategoryNames(snapshot);

        for (StagedTransaction st : stagedTransactions) {
            if (!st.isValid()) continue;

            String categoryKey = st.mappedData().categoryName().name() + ":" + st.mappedData().type();
            CategoryBreakdownBuilder builder = builderMap.computeIfAbsent(categoryKey,
                    k -> new CategoryBreakdownBuilder(
                            st.mappedData().categoryName().name(),
                            st.mappedData().parentCategoryName() != null
                                    ? st.mappedData().parentCategoryName().name() : null,
                            st.mappedData().type(),
                            st.mappedData().money().getCurrency(),
                            existingCategories
                    ));

            builder.addTransaction(st.mappedData().money());
        }

        return builderMap.values().stream()
                .map(CategoryBreakdownBuilder::build)
                .toList();
    }

    private static class CategoryBreakdownBuilder {
        private final String categoryName;
        private final String parentCategory;
        private final Type type;
        private final String currency;
        private final Set<String> existingCategories;
        private int count = 0;
        private BigDecimal totalAmount = BigDecimal.ZERO;

        CategoryBreakdownBuilder(String categoryName, String parentCategory, Type type,
                                 String currency, Set<String> existingCategories) {
            this.categoryName = categoryName;
            this.parentCategory = parentCategory;
            this.type = type;
            this.currency = currency;
            this.existingCategories = existingCategories;
        }

        void addTransaction(Money money) {
            count++;
            totalAmount = totalAmount.add(money.getAmount());
        }

        GetStagingPreviewResult.CategoryBreakdown build() {
            boolean isNewCategory = !existingCategories.contains(categoryName);
            return new GetStagingPreviewResult.CategoryBreakdown(
                    categoryName,
                    parentCategory,
                    count,
                    Money.of(totalAmount.doubleValue(), currency),
                    type,
                    isNewCategory
            );
        }
    }

    private Set<String> getExistingCategoryNames(CashFlowSnapshot snapshot) {
        Set<String> names = new HashSet<>();
        collectCategoryNames(snapshot.inflowCategories(), names);
        collectCategoryNames(snapshot.outflowCategories(), names);
        return names;
    }

    private void collectCategoryNames(List<Category> categories, Set<String> names) {
        for (Category cat : categories) {
            names.add(cat.getCategoryName().name());
            collectCategoryNames(cat.getSubCategories(), names);
        }
    }

    private List<GetStagingPreviewResult.CategoryToCreate> buildCategoriesToCreate(
            List<StagedTransaction> stagedTransactions,
            CashFlowSnapshot snapshot,
            Map<MappingKey, CategoryMapping> mappingMap) {

        Set<String> existingCategories = getExistingCategoryNames(snapshot);
        Set<String> newCategoriesAdded = new HashSet<>();
        List<GetStagingPreviewResult.CategoryToCreate> result = new ArrayList<>();

        for (StagedTransaction st : stagedTransactions) {
            if (!st.isValid()) continue;

            String categoryName = st.mappedData().categoryName().name();
            String categoryKey = categoryName + ":" + st.mappedData().type();

            if (!existingCategories.contains(categoryName) && !newCategoriesAdded.contains(categoryKey)) {
                MappingKey mappingKey = new MappingKey(st.originalData().bankCategory(), st.mappedData().type());
                CategoryMapping mapping = mappingMap.get(mappingKey);

                if (mapping != null && (mapping.action() == MappingAction.CREATE_NEW ||
                        mapping.action() == MappingAction.CREATE_SUBCATEGORY)) {

                    result.add(new GetStagingPreviewResult.CategoryToCreate(
                            categoryName,
                            st.mappedData().parentCategoryName() != null
                                    ? st.mappedData().parentCategoryName().name() : null,
                            st.mappedData().type()
                    ));
                    newCategoriesAdded.add(categoryKey);
                }
            }
        }

        return result;
    }

    private List<GetStagingPreviewResult.MonthlyBreakdown> buildMonthlyBreakdown(
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

        GetStagingPreviewResult.MonthlyBreakdown build() {
            return new GetStagingPreviewResult.MonthlyBreakdown(
                    month.toString(),
                    Money.of(inflowTotal.doubleValue(), currency),
                    Money.of(outflowTotal.doubleValue(), currency),
                    count
            );
        }
    }
}
