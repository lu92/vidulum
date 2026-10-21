package com.multi.vidulum.bank_data_ingestion.app.commands.revalidate_staging;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.bank_data_ingestion.app.BankDataIngestionConfig;
import com.multi.vidulum.cashflow.api.CashFlowModuleApi;
import com.multi.vidulum.cashflow.api.view.CashFlowSkeletonView;
import com.multi.vidulum.cashflow.api.view.ImportedTransaction;
import com.multi.vidulum.cashflow.api.view.ImportedTransactions;
import com.multi.vidulum.bank_data_ai_categorization.api.PatternMatches;
import com.multi.vidulum.bank_data_ai_categorization.api.TransactionCategorizationApi;
import com.multi.vidulum.bank_data_ai_categorization.api.TransactionToMatch;
import com.multi.vidulum.bank_data_ingestion.domain.*;
import com.multi.vidulum.bank_data_ingestion.infrastructure.StagingSessionMongoRepository;
import com.multi.vidulum.bank_data_ingestion.infrastructure.entity.StagingSessionEntity;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.cashflow.api.Type;
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
    private final StagingSessionMongoRepository stagingSessionRepository;
    private final CategoryMappingRepository categoryMappingRepository;
    private final TransactionCategorizationApi categorizationApi;
    private final CashFlowModuleApi cashFlowApi;
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
        // No try/catch: the client throws CashFlowNotFoundException from cashflow-api, which
        // carries an ErrorCode and answers 404 on its own.
        // Skeleton plus the ids it deliberately does not carry - see
        // CashFlowModuleApi.importedSourceIds for why that is a second call and not a field.
        CashFlowSkeletonView skeleton = cashFlowApi.skeletonOf(command.cashFlowId());
        ImportedTransactions alreadyImported = cashFlowApi.alreadyImported(command.cashFlowId());

        // Load current mappings
        List<CategoryMapping> mappings = categoryMappingRepository.findByCashFlowId(command.cashFlowId());
        Map<MappingKey, CategoryMapping> mappingMap = buildMappingMap(mappings);

        // What this cash flow taught AI categorization, asked once - and asked for matches, not
        // for its storage. The rule that answers is the rule the patterns were learned under, which
        // the copy that stood here was not (see LearnedPatternMatcher).
        PatternMatches learnedPatterns = categorizationApi.matchLearned(
                command.cashFlowId(), toMatch(stagedTransactions));
        log.debug("Learned patterns recognised {} of {} staged transactions for CashFlow [{}]",
                learnedPatterns.size(), stagedTransactions.size(), command.cashFlowId().id());

        // Revalidate pending transactions
        List<StagedTransaction> updatedTransactions = new ArrayList<>();
        Set<String> stillUnmappedCategories = new HashSet<>();
        int revalidatedCount = 0;

        int directMatchedCount = 0;
        int patternMatchedCount = 0;
        int reCategorizedCount = 0;
        for (StagedTransaction st : stagedTransactions) {
            // Priority -1 (absolute highest): self-transfers are never re-categorized.
            // They were detected by IBAN match in StageTransactionsCommandHandler and must
            // remain as-is regardless of pattern matching results.
            if (st.mappedData() != null && st.mappedData().selfTransfer()) {
                updatedTransactions.add(st);
                continue;
            }

            String bankCategory = st.originalData().bankCategory();

            // PRIORITY 1 (HIGHEST): Try pattern matching FIRST for ALL transactions
            // This allows re-categorization of transactions that were initially mapped
            // to generic categories (e.g., "Inne wydatki") to more specific ones (e.g., "Urzad skarbowy")
            PatternMatchResult patternMatch = matchFor(learnedPatterns, st);

            Optional<CategoryName> patternMatchCategory = patternMatch != null
                    ? skeleton.findCategoryIgnoringCase(patternMatch.categoryName(), st.originalData().type())
                    : Optional.empty();

            if (patternMatchCategory.isPresent()) {
                CategoryName actualCategoryName = patternMatchCategory.get();

                // Check if we should re-categorize an already-mapped transaction
                boolean wasAlreadyMapped = !st.isPendingMapping();
                boolean shouldReCategorize = wasAlreadyMapped &&
                        !actualCategoryName.name().equalsIgnoreCase(st.mappedData().categoryName().name());

                if (st.isPendingMapping() || shouldReCategorize) {
                    // Pattern matched to existing category
                    StagedTransaction revalidated = revalidateTransactionWithPattern(st, patternMatch, actualCategoryName, skeleton, alreadyImported, now);
                    updatedTransactions.add(revalidated);
                    revalidatedCount++;
                    patternMatchedCount++;
                    if (shouldReCategorize) {
                        reCategorizedCount++;
                        log.debug("Transaction [{}] RE-CATEGORIZED from [{}] to [{}] via pattern [{}]",
                                st.originalData().name(),
                                st.mappedData().categoryName().name(),
                                actualCategoryName,
                                patternMatch.pattern());
                    } else {
                        log.trace("Transaction [{}] matched pattern [{}] -> category [{}]",
                                st.originalData().name(), patternMatch.pattern(), actualCategoryName);
                    }
                    continue;
                }
            }

            // If already mapped and pattern matching didn't provide a better category, keep as-is
            if (!st.isPendingMapping()) {
                updatedTransactions.add(st);
                continue;
            }

            // From here, only process PENDING_MAPPING transactions

            // Priority 2: Direct bankCategory match to existing CashFlow category (case-insensitive)
            // This is most useful for banks like Pekao that provide detailed category names
            Optional<CategoryName> directMatch = skeleton.findCategoryIgnoringCase(bankCategory, st.originalData().type());
            if (directMatch.isPresent()) {
                CategoryName actualCategoryName = directMatch.get();
                CategoryName parentCategoryName = skeleton.parentOf(
                        actualCategoryName, st.originalData().type());

                StagedTransaction revalidated = revalidateTransactionWithDirectMatch(
                        st, actualCategoryName, parentCategoryName, skeleton, alreadyImported, now);
                updatedTransactions.add(revalidated);
                revalidatedCount++;
                directMatchedCount++;
                log.trace("Transaction [{}] direct matched bankCategory [{}] to existing category [{}]",
                        st.originalData().name(), bankCategory, actualCategoryName);
                continue;
            }

            // Priority 3: Try category mapping (bankCategory -> targetCategory)
            MappingKey key = new MappingKey(bankCategory, st.originalData().type());
            CategoryMapping mapping = mappingMap.get(key);

            if (mapping != null) {
                // Create mapped data and revalidate
                StagedTransaction revalidated = revalidateTransaction(st, mapping, skeleton, alreadyImported, now);
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

        // Save updated transactions
        stagedTransactionRepository.saveAll(updatedTransactions);

        log.info("Revalidated {} transactions for session [{}] ({} direct-matched, {} pattern-matched, {} re-categorized), {} still pending",
                revalidatedCount, command.stagingSessionId().id(), directMatchedCount, patternMatchedCount, reCategorizedCount,
                updatedTransactions.stream().filter(StagedTransaction::isPendingMapping).count());

        // Build summary
        int total = updatedTransactions.size();
        int stillPending = (int) updatedTransactions.stream().filter(StagedTransaction::isPendingMapping).count();
        int valid = (int) updatedTransactions.stream().filter(StagedTransaction::isValid).count();
        int invalid = (int) updatedTransactions.stream().filter(StagedTransaction::isInvalid).count();
        int duplicates = (int) updatedTransactions.stream().filter(StagedTransaction::isDuplicate).count();

        // Update StagingSessionEntity
        StagingSessionEntity sessionEntity = stagingSessionRepository
                .findBySessionId(command.stagingSessionId().id())
                .orElse(null);
        if (sessionEntity != null) {
            sessionEntity.updateSummary(total, valid, invalid, duplicates, stillPending);
            stagingSessionRepository.save(sessionEntity);
            log.debug("Updated session entity status: {}", sessionEntity.getStatus());
        }

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
            String pattern
    ) {}

    /**
     * Finds the best matching pattern for a transaction name.
     * Returns null if no pattern matches.
     *
     * Matching is case-insensitive using toUpperCase().
     * Pattern must be an exact substring of the transaction name.
     */
    /**
     * What the pattern owner recognised for this row, keyed by the row's own id.
     */
    private static PatternMatchResult matchFor(PatternMatches learnedPatterns, StagedTransaction staged) {
        return learnedPatterns.forTransaction(staged.stagedTransactionId().id())
                .map(match -> new PatternMatchResult(match.category(), match.type(), match.pattern()))
                .orElse(null);
    }

    /**
     * The staged rows as the pattern owner wants to be asked.
     */
    private static List<TransactionToMatch> toMatch(List<StagedTransaction> stagedTransactions) {
        return stagedTransactions.stream()
                .map(staged -> new TransactionToMatch(
                        staged.stagedTransactionId().id(),
                        staged.originalData().name(),
                        staged.originalData().type()))
                .toList();
    }

    /**
     * Revalidate transaction using direct bankCategory match.
     * This is Priority 0 - when bankCategory exactly matches existing CashFlow category name.
     */
    private StagedTransaction revalidateTransactionWithDirectMatch(
            StagedTransaction original,
            CategoryName matchedCategoryName,
            CategoryName parentCategoryName,
            CashFlowSkeletonView skeleton,
            ImportedTransactions alreadyImported,
            ZonedDateTime now) {

        // Create mapped data using bankCategory as the target category
        MappedTransactionData mappedData = new MappedTransactionData(
                original.originalData().name(),
                original.originalData().description(),
                matchedCategoryName,
                parentCategoryName,
                original.originalData().money(),
                original.originalData().type(),
                original.originalData().paidDate(),
                original.originalData().merchant(),
                original.originalData().merchantConfidence(),
                false
        );

        // Validate
        TransactionValidation validation = validateTransaction(original, skeleton, alreadyImported, now);

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
            CategoryName actualCategoryName,
            CashFlowSkeletonView skeleton,
            ImportedTransactions alreadyImported,
            ZonedDateTime now) {

        // Look up parent category from CashFlow structure (case-insensitive)
        CategoryName parentCategoryName = skeleton.parentOf(
                actualCategoryName, patternMatch.type());

        // Create mapped data with actual category name (correct case)
        MappedTransactionData mappedData = new MappedTransactionData(
                original.originalData().name(),
                original.originalData().description(),
                actualCategoryName,
                parentCategoryName,
                original.originalData().money(),
                original.originalData().type(),
                original.originalData().paidDate(),
                original.originalData().merchant(),
                original.originalData().merchantConfidence(),
                false
        );

        // Validate
        TransactionValidation validation = validateTransaction(original, skeleton, alreadyImported, now);

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
            CashFlowSkeletonView skeleton,
            ImportedTransactions alreadyImported,
            ZonedDateTime now) {

        // Create mapped data
        MappedTransactionData mappedData = new MappedTransactionData(
                original.originalData().name(),
                original.originalData().description(),
                mapping.targetCategoryName(),
                mapping.parentCategoryName(),
                original.originalData().money(),
                original.originalData().type(),
                original.originalData().paidDate(),
                original.originalData().merchant(),
                original.originalData().merchantConfidence(),
                false
        );

        // Validate
        TransactionValidation validation = validateTransaction(original, skeleton, alreadyImported, now);

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
            CashFlowSkeletonView skeleton,
            ImportedTransactions alreadyImported,
            ZonedDateTime now) {

        Optional<ImportedTransaction> alreadyHere =
                alreadyImported.find(st.originalData().bankTransactionId());
        if (alreadyHere.isPresent()) {
            return TransactionValidation.duplicate(alreadyHere.get().cashChangeId());
        }

        // The fourth copy of cashflow's date rules, now gone. Step 18 replaced the one in
        // StageTransactionsCommandHandler; this handler and ForceUncategorizedCommandHandler had
        // their own, with their own wording - "paidDate %s is not before activePeriod %s" here
        // against "In SETUP mode, paidDate %s must be before activePeriod %s" there, for the same
        // condition.
        return skeleton.importWindow()
                .refusalFor(st.originalData().paidDate(), now)
                .map(refusal -> TransactionValidation.invalid(List.of(refusal.getMessage())))
                .orElseGet(TransactionValidation::valid);
    }
}
