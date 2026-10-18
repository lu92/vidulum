package com.multi.vidulum.bank_data_ingestion.app.commands.force_uncategorized;

import com.multi.vidulum.bank_data_ingestion.domain.*;
import com.multi.vidulum.bank_data_ingestion.infrastructure.StagingSessionMongoRepository;
import com.multi.vidulum.bank_data_ingestion.infrastructure.entity.StagingSessionEntity;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.cashflow.api.CashFlowModuleApi;
import com.multi.vidulum.cashflow.api.view.CashFlowSkeletonView;
import com.multi.vidulum.cashflow.api.view.ImportedTransaction;
import com.multi.vidulum.cashflow.api.view.ImportedTransactions;
import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.cashflow.api.error.CategoryAlreadyExistsException;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Handler for forcing all PENDING_MAPPING transactions to use "Uncategorized" category.
 *
 * This is an explicit user action to proceed with import without categorizing transactions.
 * The "Uncategorized" category will be created automatically (for both INFLOW and OUTFLOW)
 * if it doesn't exist in the CashFlow.
 */
@Slf4j
@Component
@AllArgsConstructor
public class ForceUncategorizedCommandHandler
        implements CommandHandler<ForceUncategorizedCommand, ForceUncategorizedResult> {

    private static final String UNCATEGORIZED_CATEGORY = "Uncategorized";

    private final StagedTransactionRepository stagedTransactionRepository;
    private final StagingSessionMongoRepository stagingSessionRepository;
    private final CashFlowModuleApi cashFlowApi;
    private final Clock clock;

    @Override
    public ForceUncategorizedResult handle(ForceUncategorizedCommand command) {
        ZonedDateTime now = ZonedDateTime.now(clock);

        // Load staged transactions
        List<StagedTransaction> stagedTransactions =
                stagedTransactionRepository.findByStagingSessionId(command.stagingSessionId());

        if (stagedTransactions.isEmpty()) {
            log.warn("No staged transactions found for session [{}]", command.stagingSessionId().id());
            return new ForceUncategorizedResult(
                    command.cashFlowId(),
                    command.stagingSessionId(),
                    ForceUncategorizedResult.Status.SESSION_NOT_FOUND,
                    0,
                    false,
                    new ForceUncategorizedResult.ValidationSummary(0, 0, 0, 0, false)
            );
        }

        // Check if session has expired
        ZonedDateTime expiresAt = stagedTransactions.get(0).expiresAt();
        if (expiresAt.isBefore(now)) {
            log.warn("Staging session [{}] has expired", command.stagingSessionId().id());
            return new ForceUncategorizedResult(
                    command.cashFlowId(),
                    command.stagingSessionId(),
                    ForceUncategorizedResult.Status.SESSION_EXPIRED,
                    0,
                    false,
                    new ForceUncategorizedResult.ValidationSummary(0, 0, 0, 0, false)
            );
        }

        // Load CashFlow info
        // No try/catch: the client throws CashFlowNotFoundException from cashflow-api, which
        // carries an ErrorCode and answers 404 on its own.
        // Skeleton plus the one thing it deliberately does not carry. Two calls instead of
        // getCashFlowInfo's two HTTP requests, and neither drags every transaction along.
        CashFlowSkeletonView skeleton = cashFlowApi.skeletonOf(command.cashFlowId());
        ImportedTransactions alreadyImported = cashFlowApi.alreadyImported(command.cashFlowId());

        // Count PENDING_MAPPING transactions
        List<StagedTransaction> pendingTransactions = stagedTransactions.stream()
                .filter(StagedTransaction::isPendingMapping)
                .toList();

        if (pendingTransactions.isEmpty()) {
            log.info("No pending transactions to force uncategorized for session [{}]",
                    command.stagingSessionId().id());
            return buildResult(
                    command,
                    ForceUncategorizedResult.Status.NO_PENDING_TRANSACTIONS,
                    0,
                    false,
                    stagedTransactions,
                    skeleton,
                    now
            );
        }

        // Check if we need to create "Uncategorized" category
        boolean categoryCreated = false;
        boolean hasInflowPending = pendingTransactions.stream().anyMatch(t -> t.originalData().type() == Type.INFLOW);
        boolean hasOutflowPending = pendingTransactions.stream().anyMatch(t -> t.originalData().type() == Type.OUTFLOW);

        if (!skeleton.allCategoryNames().contains(new CategoryName(UNCATEGORIZED_CATEGORY))) {
            // Create Uncategorized category for both types if needed
            if (hasOutflowPending) {
                try {
                    cashFlowApi.createCategory(
                            command.cashFlowId(),
                            new CategoryName(UNCATEGORIZED_CATEGORY),
                            null, // top-level
                            Type.OUTFLOW,
                            true);
                    categoryCreated = true;
                    log.info("Created 'Uncategorized' OUTFLOW category for CashFlow [{}]",
                            command.cashFlowId().id());
                } catch (CategoryAlreadyExistsException e) {
                    // Race condition - category already exists
                    log.debug("Uncategorized OUTFLOW category already exists");
                }
            }
            if (hasInflowPending) {
                try {
                    cashFlowApi.createCategory(
                            command.cashFlowId(),
                            new CategoryName(UNCATEGORIZED_CATEGORY),
                            null, // top-level
                            Type.INFLOW,
                            true);
                    categoryCreated = true;
                    log.info("Created 'Uncategorized' INFLOW category for CashFlow [{}]",
                            command.cashFlowId().id());
                } catch (CategoryAlreadyExistsException e) {
                    // Race condition - category already exists
                    log.debug("Uncategorized INFLOW category already exists");
                }
            }
        }

        // Update all PENDING_MAPPING transactions to use Uncategorized
        List<StagedTransaction> updatedTransactions = new ArrayList<>();
        int updatedCount = 0;

        for (StagedTransaction st : stagedTransactions) {
            // Self-transfers are never overridden — they were detected by IBAN match
            if (st.mappedData() != null && st.mappedData().selfTransfer()) {
                updatedTransactions.add(st);
                continue;
            }

            if (st.isPendingMapping()) {
                // Create mapped data with Uncategorized
                MappedTransactionData mappedData = new MappedTransactionData(
                        st.originalData().name(),
                        st.originalData().description(),
                        new CategoryName(UNCATEGORIZED_CATEGORY),
                        null, // no parent
                        st.originalData().money(),
                        st.originalData().type(),
                        st.originalData().paidDate(),
                        st.originalData().merchant(),
                        st.originalData().merchantConfidence(),
                        false
                );

                // Validate transaction
                TransactionValidation validation = validateTransaction(st, skeleton, alreadyImported, now);

                StagedTransaction updated = new StagedTransaction(
                        st.stagedTransactionId(),
                        st.cashFlowId(),
                        st.stagingSessionId(),
                        st.originalData(),
                        mappedData,
                        validation,
                        st.createdAt(),
                        st.expiresAt()
                );
                updatedTransactions.add(updated);
                updatedCount++;
            } else {
                updatedTransactions.add(st);
            }
        }

        // Save updated transactions
        stagedTransactionRepository.saveAll(updatedTransactions);

        log.info("Forced {} transactions to Uncategorized for session [{}], category created: {}",
                updatedCount, command.stagingSessionId().id(), categoryCreated);

        return buildResult(
                command,
                ForceUncategorizedResult.Status.SUCCESS,
                updatedCount,
                categoryCreated,
                updatedTransactions,
                skeleton,
                now
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

        // Cashflow's own rule, not a third copy of it. Step 18 replaced the copy in
        // StageTransactionsCommandHandler and I claimed at the time that the rule had existed
        // twice; it had existed four times - the aggregate and three handlers in this module. This
        // was one of the two I missed.
        return skeleton.importWindow()
                .refusalFor(st.originalData().paidDate(), now)
                .map(refusal -> TransactionValidation.invalid(List.of(refusal.getMessage())))
                .orElseGet(TransactionValidation::valid);
    }

    private ForceUncategorizedResult buildResult(
            ForceUncategorizedCommand command,
            ForceUncategorizedResult.Status status,
            int updatedCount,
            boolean categoryCreated,
            List<StagedTransaction> transactions,
            CashFlowSkeletonView skeleton,
            ZonedDateTime now) {

        int total = transactions.size();
        int valid = (int) transactions.stream().filter(StagedTransaction::isValid).count();
        int invalid = (int) transactions.stream().filter(StagedTransaction::isInvalid).count();
        int duplicates = (int) transactions.stream().filter(StagedTransaction::isDuplicate).count();
        int pending = (int) transactions.stream().filter(StagedTransaction::isPendingMapping).count();

        boolean readyForImport = pending == 0 && invalid == 0;

        // Update StagingSessionEntity
        StagingSessionEntity sessionEntity = stagingSessionRepository
                .findBySessionId(command.stagingSessionId().id())
                .orElse(null);
        if (sessionEntity != null) {
            // Mark AI categorization as SKIPPED since user chose to force uncategorized
            sessionEntity.skipAiCategorization();
            sessionEntity.updateSummary(total, valid, invalid, duplicates, pending);
            stagingSessionRepository.save(sessionEntity);
            log.debug("Updated session entity status after force-uncategorized: {}, aiStatus: {}",
                    sessionEntity.getStatus(), sessionEntity.getAiCategorizationStatus());
        }

        return new ForceUncategorizedResult(
                command.cashFlowId(),
                command.stagingSessionId(),
                status,
                updatedCount,
                categoryCreated,
                new ForceUncategorizedResult.ValidationSummary(
                        total,
                        valid,
                        invalid,
                        duplicates,
                        readyForImport
                )
        );
    }
}
