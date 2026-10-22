package com.multi.vidulum.bank_data_ingestion.app.commands.stage_transactions;

import com.multi.vidulum.bank_data_ai_categorization.api.PatternMatches;
import com.multi.vidulum.bank_data_ai_categorization.api.TransactionCategorizationApi;
import com.multi.vidulum.bank_data_ai_categorization.api.TransactionToMatch;
import com.multi.vidulum.bank_data_ingestion.BankDataIngestionConfig;
import com.multi.vidulum.bank_data_ingestion.app.OwnedAccountRegistry;
import com.multi.vidulum.bank_data_ingestion.canonical.*;
import com.multi.vidulum.bank_data_ingestion.mappings.domain.*;
import com.multi.vidulum.bank_data_ingestion.domain.*;
import com.multi.vidulum.bank_data_ingestion.infrastructure.StagingSessionMongoRepository;
import com.multi.vidulum.bank_data_ingestion.infrastructure.entity.StagingSessionEntity;
import com.multi.vidulum.cashflow.api.CashFlowModuleApi;
import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.cashflow.api.forecast.CashFlowForecastApi;
import com.multi.vidulum.cashflow.api.forecast.MonthStatus;
import com.multi.vidulum.cashflow.api.forecast.MonthStatuses;
import com.multi.vidulum.cashflow.api.view.CashFlowSkeletonView;
import com.multi.vidulum.cashflow.api.view.ImportedTransaction;
import com.multi.vidulum.cashflow.api.view.ImportedTransactions;
import com.multi.vidulum.common.BankAccountId;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import com.multi.vidulum.user_financial_profile.api.UserFinancialProfileApi;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.*;

@Slf4j
@Component
@AllArgsConstructor
public class StageTransactionsCommandHandler
        implements CommandHandler<StageTransactionsCommand, StageTransactionsResult> {

    private final StagedTransactionRepository stagedTransactionRepository;
    private final StagingSessionMongoRepository stagingSessionRepository;
    private final CategoryMappingRepository categoryMappingRepository;
    private final TransactionCategorizationApi categorizationApi;
    private final CashFlowModuleApi cashFlowApi;
    private final CashFlowForecastApi cashFlowForecastApi;
    private final BankDataIngestionConfig config;
    private final Clock clock;
    private final UserFinancialProfileApi userFinancialProfileApi;

    @Override
    public StageTransactionsResult handle(StageTransactionsCommand command) {
        // The contract, in one request, in process. This used to be
        // cashFlowServiceClient.getCashFlowInfo(...) - a port this module declared itself, whose only
        // production implementation was HTTP, so staging a statement opened a connection to this
        // application's own /cash-flow/cf={id}. Everything below is something the skeleton already
        // carried; the last thing it did not, month statuses, became CashFlowForecastApi.
        // No try/catch: skeletonOf throws CashFlowNotFoundException from cashflow-api, which carries
        // an ErrorCode and answers 404 on its own.
        CashFlowSkeletonView skeleton = cashFlowApi.skeletonOf(command.cashFlowId());

        ZonedDateTime now = ZonedDateTime.now(clock);
        StagingSessionId stagingSessionId = StagingSessionId.generate();

        // Load owned accounts for self-transfer detection (single HTTP call per session)
        OwnedAccountRegistry ownedAccounts = OwnedAccountRegistry.from(userFinancialProfileApi, skeleton.owner());

        // Load all category mappings for this CashFlow
        List<CategoryMapping> mappings = categoryMappingRepository.findByCashFlowId(command.cashFlowId());
        Map<MappingKey, CategoryMapping> mappingMap = buildMappingMap(mappings);

        // What this cash flow has already taught AI categorization, asked once for the whole
        // upload. This used to fetch every pattern document and match names here, with a rule
        // (uppercase + contains) that was not the rule the patterns were learned under - so a
        // three-letter pattern like BP or MOL matched any name containing those letters. The owner
        // of the patterns answers now, and answers with matches rather than with its storage.
        PatternMatches learnedPatterns = categorizationApi.matchLearned(
                command.cashFlowId(), toMatch(command.transactions()));
        log.debug("Learned patterns recognised {} of {} transactions for CashFlow [{}]",
                learnedPatterns.size(), command.transactions().size(), command.cashFlowId().id());

        // Find unmapped categories (considering pattern mappings)
        List<StageTransactionsResult.UnmappedCategory> unmappedCategories = findUnmappedCategories(
                command.transactions(), mappingMap, learnedPatterns, skeleton);

        // Process and stage ALL transactions (even those with unmapped categories)
        List<StagedTransaction> stagedTransactions = new ArrayList<>();
        // Not part of the skeleton, and deliberately so: the duplicate list this handler builds is
        // what a person reads, so it needs the cash change ids and enough of each transaction to
        // judge the collision - two visits to one shop on one day for one amount is a real case.
        ImportedTransactions alreadyImported = cashFlowApi.alreadyImported(command.cashFlowId());

        // The forecast's contract, asked once for the whole upload rather than once per row. A
        // statement of 402 rows would otherwise be 402 questions about which months are writable,
        // and until now each of them travelled over HTTP - to this application's own endpoint.
        MonthStatuses monthStatuses = cashFlowForecastApi.monthStatusesOf(command.cashFlowId());

        int patternMatchedCount = 0;
        for (int i = 0; i < command.transactions().size(); i++) {
            StageTransactionsCommand.BankTransaction txn = command.transactions().get(i);
            PatternMatchResult patternMatch = matchFor(learnedPatterns, i);
            if (patternMatch != null) {
                patternMatchedCount++;
            }
            StagedTransaction staged = processTransaction(
                    txn, command.cashFlowId(), stagingSessionId, skeleton, monthStatuses,
                    mappingMap, patternMatch, alreadyImported, ownedAccounts, now);
            stagedTransactions.add(staged);
        }

        // Save all staged transactions (including those pending mapping)
        stagedTransactionRepository.saveAll(stagedTransactions);

        // Calculate summary counts
        int totalCount = stagedTransactions.size();
        int validCount = (int) stagedTransactions.stream().filter(StagedTransaction::isValid).count();
        int invalidCount = (int) stagedTransactions.stream().filter(StagedTransaction::isInvalid).count();
        int duplicateCount = (int) stagedTransactions.stream().filter(StagedTransaction::isDuplicate).count();
        int unmappedCount = (int) stagedTransactions.stream().filter(StagedTransaction::isPendingMapping).count();

        // Create and save StagingSessionEntity
        Instant expiresAt = now.plusHours(config.getStagingTtlHours()).toInstant();
        StageTransactionsCommand.SessionMetadata metadata = command.metadata();

        StagingSessionEntity sessionEntity = StagingSessionEntity.create(
                stagingSessionId.id(),
                command.cashFlowId().id(),
                metadata != null ? metadata.transformationId() : null,
                metadata != null ? metadata.detectedLanguage() : null,
                metadata != null ? metadata.detectedBank() : null,
                metadata != null ? metadata.detectedCountry() : null,
                metadata != null ? metadata.originalFileName() : null,
                metadata != null ? metadata.createdByUserId() : null,
                expiresAt,
                now.toInstant()
        );
        sessionEntity.updateSummary(totalCount, validCount, invalidCount, duplicateCount, unmappedCount);
        stagingSessionRepository.save(sessionEntity);

        log.info("Staged {} transactions for CashFlow [{}] in session [{}] ({} pattern-matched, {} unmapped)",
                stagedTransactions.size(), command.cashFlowId().id(), stagingSessionId.id(), patternMatchedCount, unmappedCount);

        // If there are unmapped categories, return HAS_UNMAPPED_CATEGORIES status
        if (!unmappedCategories.isEmpty()) {
            log.warn("Found {} unmapped categories for CashFlow [{}]",
                    unmappedCategories.size(), command.cashFlowId().id());
            return buildUnmappedResult(stagingSessionId, command.cashFlowId(), stagedTransactions,
                    unmappedCategories, now);
        }

        // Build and return result
        return buildResult(stagingSessionId, command.cashFlowId(), stagedTransactions,
                skeleton, mappingMap, now);
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

    /**
     * Result of pattern matching - contains category info from matched pattern.
     */
    private record PatternMatchResult(
            String categoryName,
            Type type,
            String pattern
    ) {}

    /**
     * Looks up what the pattern owner recognised for the row at this position.
     *
     * <p>What stood here was a matcher: uppercase the name, keep every pattern it contains, take
     * the most confident. It was a second rule for a relationship that already had one - see
     * {@code LearnedPatternMatcher} for what the two disagreed about.
     */
    private static PatternMatchResult matchFor(PatternMatches learnedPatterns, int index) {
        return learnedPatterns.forTransaction(String.valueOf(index))
                .map(match -> new PatternMatchResult(match.category(), match.type(), match.pattern()))
                .orElse(null);
    }

    /**
     * The batch as the pattern owner wants to be asked: a name, a side, and our own key for the row.
     */
    private static List<TransactionToMatch> toMatch(List<StageTransactionsCommand.BankTransaction> transactions) {
        List<TransactionToMatch> offered = new ArrayList<>(transactions.size());
        for (int i = 0; i < transactions.size(); i++) {
            offered.add(new TransactionToMatch(
                    String.valueOf(i), transactions.get(i).name(), transactions.get(i).type()));
        }
        return offered;
    }

    /**
     * Finds unmapped categories - categories that have neither:
     * - A direct match to existing CashFlow category (Priority 0)
     * - A category mapping (bank category → target category)
     * - A pattern mapping that matches the transaction name
     *
     * Transactions without mapping will be marked as PENDING_MAPPING,
     * requiring the user to explicitly choose a categorization strategy
     * (create new category, create subcategory, or map to uncategorized).
     */
    private List<StageTransactionsResult.UnmappedCategory> findUnmappedCategories(
            List<StageTransactionsCommand.BankTransaction> transactions,
            Map<MappingKey, CategoryMapping> mappingMap,
            PatternMatches learnedPatterns,
            CashFlowSkeletonView skeleton) {

        Map<MappingKey, Integer> unmappedCounts = new HashMap<>();

        for (StageTransactionsCommand.BankTransaction txn : transactions) {
            MappingKey key = new MappingKey(txn.bankCategory(), txn.type());

            // Priority 0: Check if bankCategory directly matches existing CashFlow category (case-insensitive)
            if (skeleton.findCategoryIgnoringCase(txn.bankCategory(), txn.type()).isPresent()) {
                continue; // Direct match - not unmapped
            }

            // Priority 1: Check if has category mapping
            if (mappingMap.containsKey(key)) {
                continue; // Has category mapping - not unmapped
            }

            // Priority 2: Check if has pattern matching
            PatternMatchResult patternMatch = matchFor(learnedPatterns, transactions.indexOf(txn));
            if (patternMatch != null) {
                // Pattern matched - check if target category exists in CashFlow (case-insensitive)
                if (skeleton.findCategoryIgnoringCase(patternMatch.categoryName(), txn.type()).isPresent()) {
                    continue; // Pattern matched to existing category - not unmapped
                }
            }

            // No mapping and no pattern match to existing category - count as unmapped
            unmappedCounts.merge(key, 1, Integer::sum);
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
            CashFlowSkeletonView skeleton,
            MonthStatuses monthStatuses,
            Map<MappingKey, CategoryMapping> mappingMap,
            PatternMatchResult patternMatch,
            ImportedTransactions alreadyImported,
            OwnedAccountRegistry ownedAccounts,
            ZonedDateTime now) {

        OriginalTransactionData originalData = new OriginalTransactionData(
                txn.bankTransactionId(),
                txn.name(),
                txn.description(),
                txn.bankCategory(),
                txn.money(),
                txn.type(),
                txn.paidDate(),
                txn.merchant(),
                txn.merchantConfidence(),
                txn.counterpartyAccount(),
                txn.paymentMethod(),
                txn.classification()
        );

        // Priority -1 (highest): self-transfer detection via owned account registry.
        // Deterministic check: if counterpartyAccount IBAN belongs to one of the user's owned
        // bank accounts, this is a transfer between own accounts — flag it and route to the
        // dedicated "Przelewy własne" category. Short-circuits all other priorities below.
        // See VID-161 Phase 1b for design rationale (Q1 decision: registry-only, no heuristics).
        String counterpartyAccount = txn.counterpartyAccount();
        if (counterpartyAccount != null && !counterpartyAccount.isBlank()) {
            boolean ownsCounterpartyAccount = ownedAccounts.isOwnedAccount(new BankAccountId(counterpartyAccount));
            if (ownsCounterpartyAccount) {
                log.info("Self-transfer detected: txn=[{}], counterpartyAccount=[{}], userId=[{}]",
                        txn.name(), counterpartyAccount, skeleton.owner().getId());

                MappedTransactionData mappedData = new MappedTransactionData(
                        txn.name(),
                        txn.description(),
                        new CategoryName("Przelewy własne"),
                        new CategoryName("Zarządzanie kontem"),
                        txn.money(),
                        txn.type(),
                        txn.paidDate(),
                        txn.merchant(),
                        txn.merchantConfidence(),
                        true
                );

                TransactionValidation validation = validateTransaction(txn, skeleton, monthStatuses, alreadyImported, now);

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
        }

        // Priority 0: Direct bankCategory match to existing CashFlow category (case-insensitive)
        // This is most useful for banks like Pekao that provide detailed category names
        // (e.g., "Artykuły spożywcze", "Transport") which can directly match user categories
        Optional<CategoryName> directMatchCategory = skeleton.findCategoryIgnoringCase(txn.bankCategory(), txn.type());
        if (directMatchCategory.isPresent()) {
            CategoryName actualCategoryName = directMatchCategory.get();
            // bankCategory matches existing category name directly
            CategoryName parentCategoryName = skeleton.parentOf(actualCategoryName, txn.type());

            MappedTransactionData mappedData = new MappedTransactionData(
                    txn.name(),
                    txn.description(),
                    actualCategoryName,
                    parentCategoryName,
                    txn.money(),
                    txn.type(),
                    txn.paidDate(),
                    txn.merchant(),
                    txn.merchantConfidence(),
                    false
            );

            // Validate transaction
            TransactionValidation validation = validateTransaction(txn, skeleton, monthStatuses, alreadyImported, now);

            log.trace("Transaction [{}] direct matched bankCategory [{}] to existing category [{}]",
                    txn.name(), txn.bankCategory(), actualCategoryName.name());

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

        // Priority 1: Pattern matching (if pattern matched and category exists in CashFlow)
        Optional<CategoryName> patternMatchCategory = patternMatch != null
                ? skeleton.findCategoryIgnoringCase(patternMatch.categoryName(), txn.type())
                : Optional.empty();
        if (patternMatchCategory.isPresent()) {
            CategoryName actualCategoryName = patternMatchCategory.get();
            // Look up parent category from CashFlow structure
            CategoryName parentCategoryName = skeleton.parentOf(actualCategoryName, txn.type());

            MappedTransactionData mappedData = new MappedTransactionData(
                    txn.name(),
                    txn.description(),
                    actualCategoryName,
                    parentCategoryName,
                    txn.money(),
                    txn.type(),
                    txn.paidDate(),
                    txn.merchant(),
                    txn.merchantConfidence(),
                    false
            );

            // Validate transaction
            TransactionValidation validation = validateTransaction(txn, skeleton, monthStatuses, alreadyImported, now);

            log.trace("Transaction [{}] matched pattern [{}] -> category [{}]",
                    txn.name(), patternMatch.pattern(), actualCategoryName.name());

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

        // Priority 2: Category mapping (bank category -> target category)
        MappingKey key = new MappingKey(txn.bankCategory(), txn.type());
        CategoryMapping mapping = mappingMap.get(key);

        MappedTransactionData mappedData;

        if (mapping == null) {
            // No mapping configured - mark as PENDING_MAPPING
            // User must explicitly choose: CREATE_NEW, CREATE_SUBCATEGORY, or MAP_TO_UNCATEGORIZED
            log.debug("Transaction [{}] has no mapping for bank category [{}] - marking as PENDING_MAPPING",
                    txn.name(), txn.bankCategory());

            return StagedTransaction.create(
                    cashFlowId,
                    stagingSessionId,
                    originalData,
                    null, // No mapped data - pending mapping decision
                    TransactionValidation.pendingMapping(txn.bankCategory()),
                    now,
                    config.getStagingTtlHours()
            );
        } else {
            mappedData = new MappedTransactionData(
                    txn.name(),
                    txn.description(),
                    mapping.targetCategoryName(),
                    mapping.parentCategoryName(),
                    txn.money(),
                    txn.type(),
                    txn.paidDate(),
                    txn.merchant(),
                    txn.merchantConfidence(),
                    false
            );
        }

        // Validate transaction
        TransactionValidation validation = validateTransaction(txn, skeleton, monthStatuses, alreadyImported, now);

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

    /**
     * Two rules, in this order, neither of them written here.
     *
     * <p>{@link CashFlowSkeletonView#importWindow()} is cashflow's own - the lifecycle phase and
     * both period boundaries, the necessary condition the write itself would enforce. It used to be
     * assembled in this class from a DTO's fields, with a hand-written status-to-phase
     * {@code switch}; the skeleton hands it over whole.
     *
     * <p>{@link com.multi.vidulum.cashflow.api.forecast.MonthStatuses} is the forecast's, stricter,
     * and only consulted once the window has accepted - so one bad date never yields two messages.
     */
    private TransactionValidation validateTransaction(
            StageTransactionsCommand.BankTransaction txn,
            CashFlowSkeletonView skeleton,
            MonthStatuses monthStatuses,
            ImportedTransactions alreadyImported,
            ZonedDateTime now) {

        List<String> errors = new ArrayList<>();

        // Check for duplicate
        Optional<ImportedTransaction> alreadyHere = alreadyImported.find(txn.bankTransactionId());
        if (alreadyHere.isPresent()) {
            return TransactionValidation.duplicate(alreadyHere.get().cashChangeId());
        }

        YearMonth paidPeriod = YearMonth.from(txn.paidDate());

        // Ask cashflow's own rule instead of repeating it. These five conditions - the lifecycle
        // phase, both period boundaries and the future check - used to be rewritten here as
        // String.format messages, a second copy of what ImportHistoricalCashChangeCommandHandler
        // enforces, and the two had already drifted. ImportWindow comes from the contract, so
        // staging now fails a row for exactly the reason the write would have failed it, with the
        // ErrorCode the endpoint would have answered.
        skeleton.importWindow()
                .refusalFor(txn.paidDate(), now)
                .ifPresent(refusal -> errors.add(refusal.getMessage()));

        // The second rule, and the stricter one: cashflow does not know it, because month statuses
        // belong to cashflow_forecast_processor. An ATTESTED month is read-only, which the
        // aggregate does not check at all. Only consulted once the window above has accepted, so a
        // row is never reported twice for one thing being wrong with its date.
        //
        // What is this module's own business is the wording. MonthStatuses decides whether a month
        // accepts an import - that logic belongs to the module that owns the statuses - but these
        // three sentences are about a row of somebody's bank statement, so they stay here.
        if (errors.isEmpty() && skeleton.isOpen() && !monthStatuses.acceptsImportAt(paidPeriod)) {
            Optional<MonthStatus> monthStatus = monthStatuses.at(paidPeriod);
            if (monthStatus.isEmpty()) {
                errors.add(String.format("Month %s is outside the CashFlow forecast range (start: %s).",
                        paidPeriod, skeleton.startPeriod()));
            } else if (monthStatus.get() == MonthStatus.ATTESTED) {
                errors.add(String.format("Cannot import to ATTESTED month %s. This month is read-only.",
                        paidPeriod));
            } else {
                errors.add(String.format("Month %s has status %s which does not allow import.",
                        paidPeriod, monthStatus.get()));
            }
        }

        if (!errors.isEmpty()) {
            return TransactionValidation.invalid(errors);
        }

        return TransactionValidation.valid();
    }

    private StageTransactionsResult buildUnmappedResult(
            StagingSessionId stagingSessionId,
            CashFlowId cashFlowId,
            List<StagedTransaction> stagedTransactions,
            List<StageTransactionsResult.UnmappedCategory> unmappedCategories,
            ZonedDateTime now) {

        // Calculate summary including pending mapping transactions
        int total = stagedTransactions.size();
        int valid = (int) stagedTransactions.stream().filter(StagedTransaction::isValid).count();
        int invalid = (int) stagedTransactions.stream().filter(StagedTransaction::isInvalid).count();
        int duplicates = (int) stagedTransactions.stream().filter(StagedTransaction::isDuplicate).count();
        int pendingMapping = (int) stagedTransactions.stream().filter(StagedTransaction::isPendingMapping).count();

        StageTransactionsResult.StagingSummary summary =
                new StageTransactionsResult.StagingSummary(total, valid, invalid, duplicates);

        return new StageTransactionsResult(
                stagingSessionId,
                cashFlowId,
                StageTransactionsResult.StagingStatus.HAS_UNMAPPED_CATEGORIES,
                now.plusHours(config.getStagingTtlHours()),
                summary,
                List.of(), // no category breakdown until all mappings configured
                List.of(), // no categories to create until all mappings configured
                List.of(), // no monthly breakdown until all mappings configured
                List.of(), // no duplicates info yet
                unmappedCategories
        );
    }

    private StageTransactionsResult buildResult(
            StagingSessionId stagingSessionId,
            CashFlowId cashFlowId,
            List<StagedTransaction> stagedTransactions,
            CashFlowSkeletonView skeleton,
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
                buildCategoryBreakdown(stagedTransactions, skeleton, mappingMap);

        // Build categories to create
        List<StageTransactionsResult.CategoryToCreate> categoriesToCreate =
                buildCategoriesToCreate(stagedTransactions, skeleton, mappingMap);

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
            CashFlowSkeletonView skeleton,
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

        Set<CategoryName> existingCategories = skeleton.allCategoryNames();

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
                Set<CategoryName> existingCategories,
                Map<MappingKey, CategoryMapping> mappingMap) {

            boolean isNewCategory = !existingCategories.contains(new CategoryName(categoryName));

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
            CashFlowSkeletonView skeleton,
            Map<MappingKey, CategoryMapping> mappingMap) {

        Set<CategoryName> existingCategories = skeleton.allCategoryNames();
        Set<String> newCategoriesAdded = new HashSet<>();
        List<StageTransactionsResult.CategoryToCreate> result = new ArrayList<>();

        for (StagedTransaction st : stagedTransactions) {
            if (!st.isValid()) continue;

            CategoryName category = st.mappedData().categoryName();
            String categoryName = category.name();
            String categoryKey = categoryName + ":" + st.mappedData().type();

            if (!existingCategories.contains(category) && !newCategoriesAdded.contains(categoryKey)) {
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
