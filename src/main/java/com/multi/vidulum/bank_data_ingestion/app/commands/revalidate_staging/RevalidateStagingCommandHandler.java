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
 */
@Slf4j
@Component
@AllArgsConstructor
public class RevalidateStagingCommandHandler
        implements CommandHandler<RevalidateStagingCommand, RevalidateStagingResult> {

    private final StagedTransactionRepository stagedTransactionRepository;
    private final CategoryMappingRepository categoryMappingRepository;
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

        // Revalidate pending transactions
        List<StagedTransaction> updatedTransactions = new ArrayList<>();
        Set<String> stillUnmappedCategories = new HashSet<>();
        int revalidatedCount = 0;

        for (StagedTransaction st : stagedTransactions) {
            if (st.isPendingMapping()) {
                MappingKey key = new MappingKey(st.originalData().bankCategory(), st.originalData().type());
                CategoryMapping mapping = mappingMap.get(key);

                if (mapping != null) {
                    // Create mapped data and revalidate
                    StagedTransaction revalidated = revalidateTransaction(st, mapping, cashFlowInfo, now);
                    updatedTransactions.add(revalidated);
                    revalidatedCount++;
                } else {
                    // Still no mapping
                    stillUnmappedCategories.add(st.originalData().bankCategory());
                    updatedTransactions.add(st);
                }
            } else {
                updatedTransactions.add(st);
            }
        }

        // Save updated transactions
        stagedTransactionRepository.saveAll(updatedTransactions);

        log.info("Revalidated {} transactions for session [{}], {} still pending",
                revalidatedCount, command.stagingSessionId().id(),
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
