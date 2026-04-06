package com.multi.vidulum.bank_data_ingestion.app.commands.revalidate_staging;

import com.multi.vidulum.bank_data_ingestion.app.BankDataIngestionConfig;
import com.multi.vidulum.bank_data_ingestion.app.CashFlowInfo;
import com.multi.vidulum.bank_data_ingestion.app.CashFlowServiceClient;
import com.multi.vidulum.bank_data_ingestion.domain.*;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Handler for revalidating a staging session after mappings have been configured.
 * Updates transactions that were PENDING_MAPPING to have proper mapped data and validation.
 *
 * Transactions without mapping will remain in PENDING_MAPPING state until user
 * explicitly configures mapping (via AI categorization or manual).
 */
@Slf4j
@Component
@AllArgsConstructor
public class RevalidateStagingCommandHandler
        implements CommandHandler<RevalidateStagingCommand, RevalidateStagingResult> {

    private final StagedTransactionRepository stagedTransactionRepository;
    private final CategoryMappingRepository categoryMappingRepository;
    private final PatternMappingRepository patternMappingRepository;
    private final CashFlowServiceClient cashFlowServiceClient;
    private final BankDataIngestionConfig config;
    private final Clock clock;

    @Override
    public RevalidateStagingResult handle(RevalidateStagingCommand command) {
        ZonedDateTime now = ZonedDateTime.now(clock);

        // Load staged transactions
        List<StagedTransaction> stagedTransactions =
                stagedTransactionRepository.findByStagingSessionId(command.stagingSessionId());

        if (stagedTransactions.isEmpty()) {
            log.warn("No staged transactions found for session [{}]", command.stagingSessionId().id());
            return new RevalidateStagingResult(
                    command.stagingSessionId(),
                    command.cashFlowId(),
                    RevalidateStagingResult.Status.SESSION_NOT_FOUND,
                    new RevalidateStagingResult.RevalidationSummary(0, 0, 0, 0, 0, 0),
                    List.of()
            );
        }

        // Check if session has expired
        ZonedDateTime expiresAt = stagedTransactions.get(0).expiresAt();
        if (expiresAt.isBefore(now)) {
            log.warn("Staging session [{}] has expired", command.stagingSessionId().id());
            return new RevalidateStagingResult(
                    command.stagingSessionId(),
                    command.cashFlowId(),
                    RevalidateStagingResult.Status.SESSION_EXPIRED,
                    new RevalidateStagingResult.RevalidationSummary(0, 0, 0, 0, 0, 0),
                    List.of()
            );
        }

        // Load CashFlow info for validation
        CashFlowInfo cashFlowInfo;
        try {
            cashFlowInfo = cashFlowServiceClient.getCashFlowInfo(command.cashFlowId().id());
        } catch (CashFlowServiceClient.CashFlowNotFoundException e) {
            throw new IllegalStateException("CashFlow not found: " + command.cashFlowId().id());
        }

        // Load current mappings
        List<CategoryMapping> mappings = categoryMappingRepository.findByCashFlowId(command.cashFlowId());
        Map<MappingKey, CategoryMapping> mappingMap = buildMappingMap(mappings);

        // Load pattern mappings for this CashFlow (for pattern-based categorization)
        List<PatternMapping> patternMappings = patternMappingRepository.findAllByCashFlowId(command.cashFlowId().id());
        log.debug("Loaded {} pattern mappings for CashFlow [{}]", patternMappings.size(), command.cashFlowId().id());

        // Revalidate pending transactions
        List<StagedTransaction> updatedTransactions = new ArrayList<>();
        Set<String> stillUnmappedCategories = new HashSet<>();
        int revalidatedCount = 0;

        int directMatchedCount = 0;
        int patternMatchedCount = 0;
        for (StagedTransaction st : stagedTransactions) {
            if (st.isPendingMapping()) {
                String bankCategory = st.originalData().bankCategory();

                // Priority 0: Direct bankCategory match to existing CashFlow category (case-insensitive)
                // This is most useful for banks like Pekao that provide detailed category names
                Optional<String> directMatch = cashFlowInfo.findCategoryNameIgnoreCase(bankCategory, st.originalData().type());
                if (directMatch.isPresent()) {
                    String actualCategoryName = directMatch.get();
                    CategoryName parentCategoryName = cashFlowInfo.findParentCategory(
                            actualCategoryName, st.originalData().type());

                    StagedTransaction revalidated = revalidateTransactionWithDirectMatch(
                            st, actualCategoryName, parentCategoryName, cashFlowInfo, now);
                    updatedTransactions.add(revalidated);
                    revalidatedCount++;
                    directMatchedCount++;
                    log.trace("Transaction [{}] direct matched bankCategory [{}] to existing category [{}]",
                            st.originalData().name(), bankCategory, actualCategoryName);
                    continue;
                }

                // Priority 1: Try pattern matching (case-insensitive)
                PatternMatchResult patternMatch = findMatchingPattern(
                        st.originalData().name(), st.originalData().type(), patternMappings);

                Optional<String> patternMatchCategory = patternMatch != null
                        ? cashFlowInfo.findCategoryNameIgnoreCase(patternMatch.categoryName(), st.originalData().type())
                        : Optional.empty();
                if (patternMatchCategory.isPresent()) {
                    String actualCategoryName = patternMatchCategory.get();
                    // Pattern matched to existing category
                    StagedTransaction revalidated = revalidateTransactionWithPattern(st, patternMatch, actualCategoryName, cashFlowInfo, now);
                    updatedTransactions.add(revalidated);
                    revalidatedCount++;
                    patternMatchedCount++;
                    log.trace("Transaction [{}] matched pattern [{}] -> category [{}]",
                            st.originalData().name(), patternMatch.pattern().normalizedPattern(), actualCategoryName);
                } else {
                    // Priority 2: Try category mapping
                    MappingKey key = new MappingKey(bankCategory, st.originalData().type());
                    CategoryMapping mapping = mappingMap.get(key);

                    if (mapping != null) {
                        // Create mapped data and revalidate
                        StagedTransaction revalidated = revalidateTransaction(st, mapping, cashFlowInfo, now);
                        updatedTransactions.add(revalidated);
                        revalidatedCount++;
                    } else {
                        // No mapping found - transaction stays PENDING_MAPPING
                        // User must configure mapping (via AI or manual) before import
                        stillUnmappedCategories.add(bankCategory);
                        updatedTransactions.add(st);
                        log.trace("Transaction [{}] still pending - no mapping found", st.originalData().name());
                    }
                }
            } else {
                updatedTransactions.add(st);
            }
        }

        // Save updated transactions
        stagedTransactionRepository.saveAll(updatedTransactions);

        log.info("Revalidated {} transactions for session [{}] ({} direct-matched, {} pattern-matched), {} still pending",
                revalidatedCount, command.stagingSessionId().id(), directMatchedCount, patternMatchedCount,
                updatedTransactions.stream().filter(StagedTransaction::isPendingMapping).count());

        // Build summary
        int total = updatedTransactions.size();
        int stillPending = (int) updatedTransactions.stream().filter(StagedTransaction::isPendingMapping).count();
        int valid = (int) updatedTransactions.stream().filter(StagedTransaction::isValid).count();
        int invalid = (int) updatedTransactions.stream().filter(StagedTransaction::isInvalid).count();
        int duplicates = (int) updatedTransactions.stream().filter(StagedTransaction::isDuplicate).count();

        RevalidateStagingResult.RevalidationSummary summary =
                new RevalidateStagingResult.RevalidationSummary(
                        total, revalidatedCount, stillPending, valid, invalid, duplicates);

        RevalidateStagingResult.Status status = stillPending > 0
                ? RevalidateStagingResult.Status.STILL_UNMAPPED
                : RevalidateStagingResult.Status.SUCCESS;

        return new RevalidateStagingResult(
                command.stagingSessionId(),
                command.cashFlowId(),
                status,
                summary,
                new ArrayList<>(stillUnmappedCategories)
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

    /**
     * Result of pattern matching - contains category info from matched pattern.
     */
    private record PatternMatchResult(
            String categoryName,
            Type type,
            PatternMapping pattern
    ) {}

    /**
     * Finds the best matching pattern for a transaction name.
     * Returns null if no pattern matches.
     */
    private PatternMatchResult findMatchingPattern(String transactionName, Type type, List<PatternMapping> patterns) {
        if (patterns.isEmpty() || transactionName == null) {
            return null;
        }

        String normalizedName = transactionName.toUpperCase();

        return patterns.stream()
                .filter(p -> p.categoryType() == type)
                .filter(p -> normalizedName.contains(p.normalizedPattern()))
                .max(Comparator.comparingDouble(PatternMapping::confidenceScore))
                .map(p -> new PatternMatchResult(p.suggestedCategory(), p.categoryType(), p))
                .orElse(null);
    }

    /**
     * Revalidate transaction using direct bankCategory match.
     * This is Priority 0 - when bankCategory exactly matches existing CashFlow category name.
     */
    private StagedTransaction revalidateTransactionWithDirectMatch(
            StagedTransaction original,
            String matchedCategoryName,
            CategoryName parentCategoryName,
            CashFlowInfo cashFlowInfo,
            ZonedDateTime now) {

        // Create mapped data using bankCategory as the target category
        MappedTransactionData mappedData = new MappedTransactionData(
                original.originalData().name(),
                original.originalData().description(),
                new CategoryName(matchedCategoryName),
                parentCategoryName,
                original.originalData().money(),
                original.originalData().type(),
                original.originalData().paidDate()
        );

        // Validate
        TransactionValidation validation = validateTransaction(original, cashFlowInfo, now);

        // Create new staged transaction with updated data
        return new StagedTransaction(
                original.stagedTransactionId(),
                original.cashFlowId(),
                original.stagingSessionId(),
                original.originalData(),
                mappedData,
                validation,
                original.createdAt(),
                original.expiresAt()
        );
    }

    /**
     * Revalidate transaction using pattern match result.
     */
    private StagedTransaction revalidateTransactionWithPattern(
            StagedTransaction original,
            PatternMatchResult patternMatch,
            String actualCategoryName,
            CashFlowInfo cashFlowInfo,
            ZonedDateTime now) {

        // Look up parent category from CashFlow structure (case-insensitive)
        CategoryName parentCategoryName = cashFlowInfo.findParentCategory(
                actualCategoryName, patternMatch.type());

        // Create mapped data with actual category name (correct case)
        MappedTransactionData mappedData = new MappedTransactionData(
                original.originalData().name(),
                original.originalData().description(),
                new CategoryName(actualCategoryName),
                parentCategoryName,
                original.originalData().money(),
                original.originalData().type(),
                original.originalData().paidDate()
        );

        // Validate
        TransactionValidation validation = validateTransaction(original, cashFlowInfo, now);

        // Create new staged transaction with updated data
        return new StagedTransaction(
                original.stagedTransactionId(),
                original.cashFlowId(),
                original.stagingSessionId(),
                original.originalData(),
                mappedData,
                validation,
                original.createdAt(),
                original.expiresAt()
        );
    }

    private StagedTransaction revalidateTransaction(
            StagedTransaction original,
            CategoryMapping mapping,
            CashFlowInfo cashFlowInfo,
            ZonedDateTime now) {

        // Create mapped data
        MappedTransactionData mappedData = new MappedTransactionData(
                original.originalData().name(),
                original.originalData().description(),
                mapping.targetCategoryName(),
                mapping.parentCategoryName(),
                original.originalData().money(),
                original.originalData().type(),
                original.originalData().paidDate()
        );

        // Validate
        TransactionValidation validation = validateTransaction(original, cashFlowInfo, now);

        // Create new staged transaction with updated data
        return new StagedTransaction(
                original.stagedTransactionId(),
                original.cashFlowId(),
                original.stagingSessionId(),
                original.originalData(),
                mappedData,
                validation,
                original.createdAt(),
                original.expiresAt()
        );
    }

    private TransactionValidation validateTransaction(
            StagedTransaction st,
            CashFlowInfo cashFlowInfo,
            ZonedDateTime now) {

        List<String> errors = new ArrayList<>();

        // Check for duplicate (based on existing transaction IDs)
        if (cashFlowInfo.existingTransactionIds().contains(st.originalData().bankTransactionId())) {
            return TransactionValidation.duplicate(st.originalData().bankTransactionId());
        }

        // CashFlow must be in SETUP mode for historical import
        if (!cashFlowInfo.isInSetupMode()) {
            errors.add("CashFlow is not in SETUP mode");
        }

        // paidDate validation for historical import
        YearMonth paidPeriod = YearMonth.from(st.originalData().paidDate());
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

        if (st.originalData().paidDate().isAfter(now)) {
            errors.add("paidDate cannot be in the future");
        }

        if (!errors.isEmpty()) {
            return TransactionValidation.invalid(errors);
        }

        return TransactionValidation.valid();
    }
}
