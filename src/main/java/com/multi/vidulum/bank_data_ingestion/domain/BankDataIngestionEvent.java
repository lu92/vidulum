package com.multi.vidulum.bank_data_ingestion.domain;

import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.shared.ddd.event.DomainEvent;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Sealed interface for all bank data ingestion events.
 * These events are emitted during the import job lifecycle and can be used for:
 * - Real-time progress tracking (WebSocket)
 * - Audit trail
 * - Monitoring and metrics
 */
public sealed interface BankDataIngestionEvent extends DomainEvent
        permits
        BankDataIngestionEvent.ImportJobStartedEvent,
        BankDataIngestionEvent.ImportProgressEvent,
        BankDataIngestionEvent.ImportJobCompletedEvent,
        BankDataIngestionEvent.ImportJobFailedEvent,
        BankDataIngestionEvent.ImportJobRolledBackEvent,
        BankDataIngestionEvent.ImportJobFinalizedEvent {

    String jobId();
    String cashFlowId();
    ZonedDateTime occurredAt();

    /**
     * Emitted when an import job is started.
     */
    record ImportJobStartedEvent(
            String jobId,
            String cashFlowId,
            String stagingSessionId,
            int totalTransactions,
            int validTransactions,
            int categoriesToCreate,
            ZonedDateTime occurredAt
    ) implements BankDataIngestionEvent {}

    /**
     * Emitted during import to report progress.
     * Can be used for real-time UI updates via WebSocket.
     */
    record ImportProgressEvent(
            String jobId,
            String cashFlowId,
            ImportPhase phase,
            int processed,
            int total,
            int percent,
            ZonedDateTime occurredAt
    ) implements BankDataIngestionEvent {}

    /**
     * Emitted when an import job completes successfully.
     */
    record ImportJobCompletedEvent(
            String jobId,
            String cashFlowId,
            int categoriesCreated,
            int transactionsImported,
            int transactionsFailed,
            long durationMs,
            ZonedDateTime occurredAt
    ) implements BankDataIngestionEvent {}

    /**
     * Emitted when an import job fails.
     */
    record ImportJobFailedEvent(
            String jobId,
            String cashFlowId,
            ImportPhase phase,
            String error,
            ZonedDateTime occurredAt
    ) implements BankDataIngestionEvent {}

    /**
     * Emitted when an import job is rolled back.
     */
    record ImportJobRolledBackEvent(
            String jobId,
            String cashFlowId,
            int transactionsDeleted,
            int categoriesDeleted,
            long rollbackDurationMs,
            ZonedDateTime occurredAt
    ) implements BankDataIngestionEvent {}

    /**
     * Emitted when an import job is finalized (cleanup completed).
     */
    record ImportJobFinalizedEvent(
            String jobId,
            String cashFlowId,
            int stagedTransactionsDeleted,
            int mappingsDeleted,
            ZonedDateTime occurredAt
    ) implements BankDataIngestionEvent {}
}
