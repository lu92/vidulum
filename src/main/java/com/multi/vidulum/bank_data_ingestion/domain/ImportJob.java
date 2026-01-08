package com.multi.vidulum.bank_data_ingestion.domain;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.Type;
import com.multi.vidulum.common.Money;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Aggregate representing an import job that imports staged transactions into a CashFlow.
 * Tracks progress, supports rollback, and maintains history.
 */
public record ImportJob(
        ImportJobId jobId,
        CashFlowId cashFlowId,
        StagingSessionId stagingSessionId,
        ImportJobStatus status,
        ImportTimestamps timestamps,
        ImportInput input,
        ImportProgress progress,
        ImportResult result,
        RollbackData rollbackData,
        ImportSummary summary
) {

    // ============ Factory Methods ============

    /**
     * Create a new import job in PENDING status.
     */
    public static ImportJob create(
            CashFlowId cashFlowId,
            StagingSessionId stagingSessionId,
            int totalTransactions,
            int validTransactions,
            int duplicateTransactions,
            int categoriesToCreate,
            ZonedDateTime now
    ) {
        ImportInput input = new ImportInput(
                totalTransactions,
                validTransactions,
                duplicateTransactions,
                categoriesToCreate
        );

        List<PhaseProgress> phases = new ArrayList<>();
        phases.add(PhaseProgress.pending(ImportPhase.CREATING_CATEGORIES, categoriesToCreate));
        phases.add(PhaseProgress.pending(ImportPhase.IMPORTING_TRANSACTIONS, validTransactions));

        ImportProgress progress = new ImportProgress(0, null, phases);

        ImportResult result = ImportResult.empty();

        RollbackData rollbackData = new RollbackData(
                true,
                null,
                new ArrayList<>(),
                new ArrayList<>()
        );

        return new ImportJob(
                ImportJobId.generate(),
                cashFlowId,
                stagingSessionId,
                ImportJobStatus.PENDING,
                ImportTimestamps.created(now),
                input,
                progress,
                result,
                rollbackData,
                null
        );
    }

    // ============ State Transitions ============

    /**
     * Start processing the import job.
     */
    public ImportJob startProcessing(ZonedDateTime now) {
        if (status != ImportJobStatus.PENDING) {
            throw new IllegalStateException("Cannot start processing job in status: " + status);
        }

        ImportProgress newProgress = progress.startPhase(ImportPhase.CREATING_CATEGORIES, now);

        return new ImportJob(
                jobId,
                cashFlowId,
                stagingSessionId,
                ImportJobStatus.PROCESSING,
                timestamps.withStartedAt(now),
                input,
                newProgress,
                result,
                rollbackData,
                summary
        );
    }

    /**
     * Update progress for a phase.
     */
    public ImportJob updateProgress(ImportPhase phase, int processed) {
        if (status != ImportJobStatus.PROCESSING) {
            throw new IllegalStateException("Cannot update progress for job in status: " + status);
        }

        ImportProgress newProgress = progress.updatePhaseProgress(phase, processed);

        return new ImportJob(
                jobId,
                cashFlowId,
                stagingSessionId,
                status,
                timestamps,
                input,
                newProgress,
                result,
                rollbackData,
                summary
        );
    }

    /**
     * Complete a phase and optionally start the next one.
     */
    public ImportJob completePhase(ImportPhase phase, ZonedDateTime now) {
        if (status != ImportJobStatus.PROCESSING) {
            throw new IllegalStateException("Cannot complete phase for job in status: " + status);
        }

        ImportProgress newProgress = progress.completePhase(phase, now);

        // If there's a next phase, start it
        if (phase == ImportPhase.CREATING_CATEGORIES) {
            newProgress = newProgress.startPhase(ImportPhase.IMPORTING_TRANSACTIONS, now);
        }

        return new ImportJob(
                jobId,
                cashFlowId,
                stagingSessionId,
                status,
                timestamps,
                input,
                newProgress,
                result,
                rollbackData,
                summary
        );
    }

    /**
     * Record a created category for rollback tracking.
     */
    public ImportJob recordCreatedCategory(String categoryName) {
        RollbackData newRollbackData = rollbackData.withCreatedCategory(categoryName);
        ImportResult newResult = result.withCreatedCategory(categoryName);

        return new ImportJob(
                jobId,
                cashFlowId,
                stagingSessionId,
                status,
                timestamps,
                input,
                progress,
                newResult,
                newRollbackData,
                summary
        );
    }

    /**
     * Record a created cash change for rollback tracking.
     */
    public ImportJob recordCreatedCashChange(String cashChangeId) {
        RollbackData newRollbackData = rollbackData.withCreatedCashChange(cashChangeId);
        ImportResult newResult = result.withCreatedCashChange(cashChangeId);

        return new ImportJob(
                jobId,
                cashFlowId,
                stagingSessionId,
                status,
                timestamps,
                input,
                progress,
                newResult,
                newRollbackData,
                summary
        );
    }

    /**
     * Record a failed transaction.
     */
    public ImportJob recordFailedTransaction(String bankTransactionId, String error) {
        ImportResult newResult = result.withError(bankTransactionId, error);

        return new ImportJob(
                jobId,
                cashFlowId,
                stagingSessionId,
                status,
                timestamps,
                input,
                progress,
                newResult,
                rollbackData,
                summary
        );
    }

    /**
     * Complete the import job successfully.
     */
    public ImportJob complete(ImportSummary importSummary, ZonedDateTime now, long rollbackWindowHours) {
        if (status != ImportJobStatus.PROCESSING) {
            throw new IllegalStateException("Cannot complete job in status: " + status);
        }

        ImportProgress finalProgress = progress.completePhase(ImportPhase.IMPORTING_TRANSACTIONS, now)
                .withPercentage(100)
                .withCurrentPhase(null);

        RollbackData newRollbackData = new RollbackData(
                true,
                now.plusHours(rollbackWindowHours),
                rollbackData.createdCashChangeIds(),
                rollbackData.createdCategoryNames()
        );

        return new ImportJob(
                jobId,
                cashFlowId,
                stagingSessionId,
                ImportJobStatus.COMPLETED,
                timestamps.withCompletedAt(now),
                input,
                finalProgress,
                result,
                newRollbackData,
                importSummary
        );
    }

    /**
     * Mark the import job as failed.
     */
    public ImportJob fail(String error, ZonedDateTime now) {
        ImportResult newResult = result.withGeneralError(error);

        return new ImportJob(
                jobId,
                cashFlowId,
                stagingSessionId,
                ImportJobStatus.FAILED,
                timestamps.withCompletedAt(now),
                input,
                progress,
                newResult,
                rollbackData,
                summary
        );
    }

    /**
     * Roll back the import job.
     */
    public ImportJob rollback(int transactionsDeleted, int categoriesDeleted, long durationMs, ZonedDateTime now) {
        if (!canRollback()) {
            throw new IllegalStateException("Cannot rollback job - rollback not allowed");
        }

        RollbackData newRollbackData = new RollbackData(
                false,
                null,
                List.of(),
                List.of()
        );

        ImportSummary newSummary = summary != null
                ? summary.withRollback(transactionsDeleted, categoriesDeleted, durationMs)
                : ImportSummary.forRollback(transactionsDeleted, categoriesDeleted, durationMs);

        return new ImportJob(
                jobId,
                cashFlowId,
                stagingSessionId,
                ImportJobStatus.ROLLED_BACK,
                timestamps.withRolledBackAt(now),
                input,
                progress,
                result,
                newRollbackData,
                newSummary
        );
    }

    /**
     * Finalize the import job.
     */
    public ImportJob finalize(ZonedDateTime now) {
        if (status != ImportJobStatus.COMPLETED) {
            throw new IllegalStateException("Cannot finalize job in status: " + status);
        }

        return new ImportJob(
                jobId,
                cashFlowId,
                stagingSessionId,
                ImportJobStatus.FINALIZED,
                timestamps.withFinalizedAt(now),
                input,
                progress,
                result,
                rollbackData,
                summary
        );
    }

    /**
     * Mark rollback as no longer possible (e.g., after CashFlow attestation).
     */
    public ImportJob disableRollback() {
        RollbackData newRollbackData = new RollbackData(
                false,
                null,
                rollbackData.createdCashChangeIds(),
                rollbackData.createdCategoryNames()
        );

        return new ImportJob(
                jobId,
                cashFlowId,
                stagingSessionId,
                status,
                timestamps,
                input,
                progress,
                result,
                newRollbackData,
                summary
        );
    }

    // ============ Queries ============

    /**
     * Check if rollback is possible.
     */
    public boolean canRollback() {
        return rollbackData.canRollback() &&
               (status == ImportJobStatus.COMPLETED || status == ImportJobStatus.FINALIZED);
    }

    /**
     * Check if rollback deadline has passed.
     */
    public boolean isRollbackDeadlinePassed(ZonedDateTime now) {
        return rollbackData.rollbackDeadline() != null &&
               now.isAfter(rollbackData.rollbackDeadline());
    }

    /**
     * Get elapsed time in milliseconds.
     */
    public long getElapsedTimeMs(ZonedDateTime now) {
        if (timestamps.startedAt() == null) {
            return 0;
        }
        ZonedDateTime endTime = timestamps.completedAt() != null ? timestamps.completedAt() : now;
        return java.time.Duration.between(timestamps.startedAt(), endTime).toMillis();
    }

    // ============ Nested Records ============

    /**
     * Timestamps for various stages of the import job.
     */
    public record ImportTimestamps(
            ZonedDateTime createdAt,
            ZonedDateTime startedAt,
            ZonedDateTime completedAt,
            ZonedDateTime rolledBackAt,
            ZonedDateTime finalizedAt
    ) {
        public static ImportTimestamps created(ZonedDateTime now) {
            return new ImportTimestamps(now, null, null, null, null);
        }

        public ImportTimestamps withStartedAt(ZonedDateTime startedAt) {
            return new ImportTimestamps(createdAt, startedAt, completedAt, rolledBackAt, finalizedAt);
        }

        public ImportTimestamps withCompletedAt(ZonedDateTime completedAt) {
            return new ImportTimestamps(createdAt, startedAt, completedAt, rolledBackAt, finalizedAt);
        }

        public ImportTimestamps withRolledBackAt(ZonedDateTime rolledBackAt) {
            return new ImportTimestamps(createdAt, startedAt, completedAt, rolledBackAt, finalizedAt);
        }

        public ImportTimestamps withFinalizedAt(ZonedDateTime finalizedAt) {
            return new ImportTimestamps(createdAt, startedAt, completedAt, rolledBackAt, finalizedAt);
        }
    }

    /**
     * Input statistics from staging.
     */
    public record ImportInput(
            int totalTransactions,
            int validTransactions,
            int duplicateTransactions,
            int categoriesToCreate
    ) {
    }

    /**
     * Progress tracking for the import job.
     */
    public record ImportProgress(
            int percentage,
            ImportPhase currentPhase,
            List<PhaseProgress> phases
    ) {
        public ImportProgress startPhase(ImportPhase phase, ZonedDateTime now) {
            List<PhaseProgress> newPhases = phases.stream()
                    .map(p -> p.name() == phase ? p.start(now) : p)
                    .toList();

            int newPercentage = calculatePercentage(newPhases);

            return new ImportProgress(newPercentage, phase, newPhases);
        }

        public ImportProgress updatePhaseProgress(ImportPhase phase, int processed) {
            List<PhaseProgress> newPhases = phases.stream()
                    .map(p -> p.name() == phase ? p.withProcessed(processed) : p)
                    .toList();

            int newPercentage = calculatePercentage(newPhases);

            return new ImportProgress(newPercentage, currentPhase, newPhases);
        }

        public ImportProgress completePhase(ImportPhase phase, ZonedDateTime now) {
            List<PhaseProgress> newPhases = phases.stream()
                    .map(p -> p.name() == phase ? p.complete(now) : p)
                    .toList();

            int newPercentage = calculatePercentage(newPhases);

            return new ImportProgress(newPercentage, currentPhase, newPhases);
        }

        public ImportProgress withPercentage(int percentage) {
            return new ImportProgress(percentage, currentPhase, phases);
        }

        public ImportProgress withCurrentPhase(ImportPhase phase) {
            return new ImportProgress(percentage, phase, phases);
        }

        private int calculatePercentage(List<PhaseProgress> phases) {
            int totalItems = phases.stream().mapToInt(PhaseProgress::total).sum();
            if (totalItems == 0) return 100;

            int processedItems = phases.stream().mapToInt(PhaseProgress::processed).sum();
            return (int) ((processedItems * 100.0) / totalItems);
        }
    }

    /**
     * Progress for a single phase.
     */
    public record PhaseProgress(
            ImportPhase name,
            PhaseStatus status,
            int processed,
            int total,
            ZonedDateTime startedAt,
            ZonedDateTime completedAt,
            Long durationMs
    ) {
        public static PhaseProgress pending(ImportPhase name, int total) {
            return new PhaseProgress(name, PhaseStatus.PENDING, 0, total, null, null, null);
        }

        public PhaseProgress start(ZonedDateTime now) {
            return new PhaseProgress(name, PhaseStatus.IN_PROGRESS, processed, total, now, null, null);
        }

        public PhaseProgress withProcessed(int processed) {
            return new PhaseProgress(name, status, processed, total, startedAt, completedAt, durationMs);
        }

        public PhaseProgress complete(ZonedDateTime now) {
            Long duration = startedAt != null
                    ? java.time.Duration.between(startedAt, now).toMillis()
                    : null;
            return new PhaseProgress(name, PhaseStatus.COMPLETED, total, total, startedAt, now, duration);
        }

        public PhaseProgress fail(ZonedDateTime now) {
            Long duration = startedAt != null
                    ? java.time.Duration.between(startedAt, now).toMillis()
                    : null;
            return new PhaseProgress(name, PhaseStatus.FAILED, processed, total, startedAt, now, duration);
        }
    }

    /**
     * Result of the import operation.
     */
    public record ImportResult(
            List<String> categoriesCreated,
            List<String> cashChangesCreated,
            int transactionsImported,
            int transactionsFailed,
            List<ImportError> errors
    ) {
        public static ImportResult empty() {
            return new ImportResult(new ArrayList<>(), new ArrayList<>(), 0, 0, new ArrayList<>());
        }

        public ImportResult withCreatedCategory(String categoryName) {
            List<String> newCategories = new ArrayList<>(categoriesCreated);
            newCategories.add(categoryName);
            return new ImportResult(newCategories, cashChangesCreated, transactionsImported, transactionsFailed, errors);
        }

        public ImportResult withCreatedCashChange(String cashChangeId) {
            List<String> newCashChanges = new ArrayList<>(cashChangesCreated);
            newCashChanges.add(cashChangeId);
            return new ImportResult(categoriesCreated, newCashChanges, transactionsImported + 1, transactionsFailed, errors);
        }

        public ImportResult withError(String bankTransactionId, String error) {
            List<ImportError> newErrors = new ArrayList<>(errors);
            newErrors.add(new ImportError(bankTransactionId, error));
            return new ImportResult(categoriesCreated, cashChangesCreated, transactionsImported, transactionsFailed + 1, newErrors);
        }

        public ImportResult withGeneralError(String error) {
            List<ImportError> newErrors = new ArrayList<>(errors);
            newErrors.add(new ImportError(null, error));
            return new ImportResult(categoriesCreated, cashChangesCreated, transactionsImported, transactionsFailed, newErrors);
        }
    }

    /**
     * Error during import.
     */
    public record ImportError(
            String bankTransactionId,
            String error
    ) {
    }

    /**
     * Data needed for rollback.
     */
    public record RollbackData(
            boolean canRollback,
            ZonedDateTime rollbackDeadline,
            List<String> createdCashChangeIds,
            List<String> createdCategoryNames
    ) {
        public RollbackData withCreatedCategory(String categoryName) {
            List<String> newCategories = new ArrayList<>(createdCategoryNames);
            newCategories.add(categoryName);
            return new RollbackData(canRollback, rollbackDeadline, createdCashChangeIds, newCategories);
        }

        public RollbackData withCreatedCashChange(String cashChangeId) {
            List<String> newCashChanges = new ArrayList<>(createdCashChangeIds);
            newCashChanges.add(cashChangeId);
            return new RollbackData(canRollback, rollbackDeadline, newCashChanges, createdCategoryNames);
        }
    }

    /**
     * Summary of the completed import.
     */
    public record ImportSummary(
            List<CategoryBreakdown> categoryBreakdown,
            List<MonthlyBreakdown> monthlyBreakdown,
            long totalDurationMs,
            RollbackSummary rollbackSummary
    ) {
        public static ImportSummary create(
                List<CategoryBreakdown> categoryBreakdown,
                List<MonthlyBreakdown> monthlyBreakdown,
                long totalDurationMs
        ) {
            return new ImportSummary(categoryBreakdown, monthlyBreakdown, totalDurationMs, null);
        }

        public static ImportSummary forRollback(int transactionsDeleted, int categoriesDeleted, long durationMs) {
            return new ImportSummary(
                    List.of(),
                    List.of(),
                    0,
                    new RollbackSummary(transactionsDeleted, categoriesDeleted, durationMs)
            );
        }

        public ImportSummary withRollback(int transactionsDeleted, int categoriesDeleted, long durationMs) {
            return new ImportSummary(
                    categoryBreakdown,
                    monthlyBreakdown,
                    totalDurationMs,
                    new RollbackSummary(transactionsDeleted, categoriesDeleted, durationMs)
            );
        }
    }

    /**
     * Category breakdown in summary.
     */
    public record CategoryBreakdown(
            String categoryName,
            String parentCategory,
            int transactionCount,
            Money totalAmount,
            Type type,
            boolean isNewCategory
    ) {
    }

    /**
     * Monthly breakdown in summary.
     */
    public record MonthlyBreakdown(
            String month,
            Money inflowTotal,
            Money outflowTotal,
            int transactionCount
    ) {
    }

    /**
     * Summary of rollback operation.
     */
    public record RollbackSummary(
            int transactionsDeleted,
            int categoriesDeleted,
            long rollbackDurationMs
    ) {
    }
}
