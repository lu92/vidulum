package com.multi.vidulum.bank_data_ingestion.app.commands.force_uncategorized;

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
import java.util.ArrayList;
import java.util.List;

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
    private final CashFlowServiceClient cashFlowServiceClient;
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
        CashFlowInfo cashFlowInfo;
        try {
            cashFlowInfo = cashFlowServiceClient.getCashFlowInfo(command.cashFlowId().id());
        } catch (CashFlowServiceClient.CashFlowNotFoundException e) {
            throw new IllegalStateException("CashFlow not found: " + command.cashFlowId().id());
        }

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
                    cashFlowInfo,
                    now
            );
        }

        // Check if we need to create "Uncategorized" category
        boolean categoryCreated = false;
        boolean hasInflowPending = pendingTransactions.stream().anyMatch(t -> t.originalData().type() == Type.INFLOW);
        boolean hasOutflowPending = pendingTransactions.stream().anyMatch(t -> t.originalData().type() == Type.OUTFLOW);

        if (!cashFlowInfo.getAllCategoryNames().contains(UNCATEGORIZED_CATEGORY)) {
            // Create Uncategorized category for both types if needed
            if (hasOutflowPending) {
                try {
                    cashFlowServiceClient.createCategory(
                            command.cashFlowId().id(),
                            UNCATEGORIZED_CATEGORY,
                            null, // no parent
                            Type.OUTFLOW
                    );
                    categoryCreated = true;
                    log.info("Created 'Uncategorized' OUTFLOW category for CashFlow [{}]",
                            command.cashFlowId().id());
                } catch (CashFlowServiceClient.CategoryAlreadyExistsException e) {
                    // Race condition - category already exists
                    log.debug("Uncategorized OUTFLOW category already exists");
                }
            }
            if (hasInflowPending) {
                try {
                    cashFlowServiceClient.createCategory(
                            command.cashFlowId().id(),
                            UNCATEGORIZED_CATEGORY,
                            null, // no parent
                            Type.INFLOW
                    );
                    categoryCreated = true;
                    log.info("Created 'Uncategorized' INFLOW category for CashFlow [{}]",
                            command.cashFlowId().id());
                } catch (CashFlowServiceClient.CategoryAlreadyExistsException e) {
                    // Race condition - category already exists
                    log.debug("Uncategorized INFLOW category already exists");
                }
            }
        }

        // Update all PENDING_MAPPING transactions to use Uncategorized
        List<StagedTransaction> updatedTransactions = new ArrayList<>();
        int updatedCount = 0;

        for (StagedTransaction st : stagedTransactions) {
            if (st.isPendingMapping()) {
                // Create mapped data with Uncategorized
                MappedTransactionData mappedData = new MappedTransactionData(
                        st.originalData().name(),
                        st.originalData().description(),
                        new CategoryName(UNCATEGORIZED_CATEGORY),
                        null, // no parent
                        st.originalData().money(),
                        st.originalData().type(),
                        st.originalData().paidDate()
                );

                // Validate transaction
                TransactionValidation validation = validateTransaction(st, cashFlowInfo, now);

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
                cashFlowInfo,
                now
        );
    }

    private TransactionValidation validateTransaction(
            StagedTransaction st,
            CashFlowInfo cashFlowInfo,
            ZonedDateTime now) {

        List<String> errors = new ArrayList<>();

        // Check for duplicate
        if (cashFlowInfo.existingTransactionIds().contains(st.originalData().bankTransactionId())) {
            return TransactionValidation.duplicate(st.originalData().bankTransactionId());
        }

        // paidDate validation
        YearMonth paidPeriod = YearMonth.from(st.originalData().paidDate());
        YearMonth activePeriod = cashFlowInfo.activePeriod();
        YearMonth startPeriod = cashFlowInfo.startPeriod();

        if (cashFlowInfo.isInSetupMode()) {
            if (!paidPeriod.isBefore(activePeriod)) {
                errors.add(String.format("In SETUP mode, paidDate %s must be before activePeriod %s",
                        paidPeriod, activePeriod));
            }
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

    private ForceUncategorizedResult buildResult(
            ForceUncategorizedCommand command,
            ForceUncategorizedResult.Status status,
            int updatedCount,
            boolean categoryCreated,
            List<StagedTransaction> transactions,
            CashFlowInfo cashFlowInfo,
            ZonedDateTime now) {

        int total = transactions.size();
        int valid = (int) transactions.stream().filter(StagedTransaction::isValid).count();
        int invalid = (int) transactions.stream().filter(StagedTransaction::isInvalid).count();
        int duplicates = (int) transactions.stream().filter(StagedTransaction::isDuplicate).count();
        int pending = (int) transactions.stream().filter(StagedTransaction::isPendingMapping).count();

        boolean readyForImport = pending == 0 && invalid == 0;

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
