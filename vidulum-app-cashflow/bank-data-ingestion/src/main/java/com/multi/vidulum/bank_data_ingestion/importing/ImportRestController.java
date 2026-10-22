package com.multi.vidulum.bank_data_ingestion.importing;

import com.multi.vidulum.bank_data_ingestion.app.BankDataIngestionDto;
import com.multi.vidulum.bank_data_ingestion.importing.commands.finalize_import.FinalizeImportJobCommand;
import com.multi.vidulum.bank_data_ingestion.importing.commands.finalize_import.FinalizeImportJobResult;
import com.multi.vidulum.bank_data_ingestion.importing.commands.rollback_import.RollbackImportJobCommand;
import com.multi.vidulum.bank_data_ingestion.importing.commands.rollback_import.RollbackImportJobResult;
import com.multi.vidulum.bank_data_ingestion.importing.commands.start_import.StartImportJobCommand;
import com.multi.vidulum.bank_data_ingestion.importing.commands.start_import.StartImportJobResult;
import com.multi.vidulum.bank_data_ingestion.importing.queries.get_import_progress.GetImportProgressQuery;
import com.multi.vidulum.bank_data_ingestion.importing.queries.get_import_progress.GetImportProgressResult;
import com.multi.vidulum.bank_data_ingestion.importing.queries.list_import_jobs.ListImportJobsQuery;
import com.multi.vidulum.bank_data_ingestion.importing.queries.list_import_jobs.ListImportJobsResult;
import com.multi.vidulum.bank_data_ingestion.canonical.*;
import com.multi.vidulum.bank_data_ingestion.importing.domain.*;
import com.multi.vidulum.bank_data_ingestion.domain.*;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import java.util.List;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * The import job over HTTP: start it, watch it, roll it back, finalise it, list them.

 * <p>Split out of the single 1,195-line controller. <b>The URL prefix is unchanged.</b>
 */
@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/bank-data-ingestion/cf={cashFlowId}")
public class ImportRestController {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;

    /**
     * Start an import job from staged transactions.
     * Creates categories and imports transactions into the CashFlow.
     */
    @PostMapping("/import")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BankDataIngestionDto.StartImportResponse startImport(
            @PathVariable("cashFlowId") String cashFlowId,
            @RequestBody BankDataIngestionDto.StartImportRequest request) {

        StartImportJobResult result = commandGateway.send(
                new StartImportJobCommand(
                        CashFlowId.of(cashFlowId),
                        StagingSessionId.of(request.getStagingSessionId())
                )
        );

        return toStartImportResponse(result);
    }
    /**
     * Get the progress of an import job.
     */
    @GetMapping("/import/{jobId}")
    public BankDataIngestionDto.GetImportProgressResponse getImportProgress(
            @PathVariable("cashFlowId") String cashFlowId,
            @PathVariable("jobId") String jobId) {

        GetImportProgressResult result = queryGateway.send(
                new GetImportProgressQuery(
                        CashFlowId.of(cashFlowId),
                        ImportJobId.of(jobId)
                )
        );

        return toGetImportProgressResponse(result);
    }
    /**
     * Rollback an import job - deletes imported transactions and optionally categories.
     */
    @PostMapping("/import/{jobId}/rollback")
    public BankDataIngestionDto.RollbackImportResponse rollbackImport(
            @PathVariable("cashFlowId") String cashFlowId,
            @PathVariable("jobId") String jobId) {

        RollbackImportJobResult result = commandGateway.send(
                new RollbackImportJobCommand(
                        CashFlowId.of(cashFlowId),
                        ImportJobId.of(jobId)
                )
        );

        return toRollbackImportResponse(result);
    }
    /**
     * Finalize an import job - deletes staging data and optionally category mappings.
     */
    @PostMapping("/import/{jobId}/finalize")
    public BankDataIngestionDto.FinalizeImportResponse finalizeImport(
            @PathVariable("cashFlowId") String cashFlowId,
            @PathVariable("jobId") String jobId,
            @RequestBody BankDataIngestionDto.FinalizeImportRequest request) {

        FinalizeImportJobResult result = commandGateway.send(
                new FinalizeImportJobCommand(
                        CashFlowId.of(cashFlowId),
                        ImportJobId.of(jobId),
                        request.isDeleteMappings()
                )
        );

        return toFinalizeImportResponse(result);
    }
    /**
     * List all import jobs for a CashFlow.
     */
    @GetMapping("/import")
    public BankDataIngestionDto.ListImportJobsResponse listImportJobs(
            @PathVariable("cashFlowId") String cashFlowId,
            @RequestParam(value = "status", required = false) List<String> statuses) {

        List<ImportJobStatus> statusFilters = statuses != null
                ? statuses.stream().map(ImportJobStatus::valueOf).toList()
                : null;

        ListImportJobsResult result = queryGateway.send(
                new ListImportJobsQuery(
                        CashFlowId.of(cashFlowId),
                        statusFilters
                )
        );

        return toListImportJobsResponse(result);
    }
    private BankDataIngestionDto.StartImportResponse toStartImportResponse(StartImportJobResult result) {
        return BankDataIngestionDto.StartImportResponse.builder()
                .jobId(result.jobId().id())
                .cashFlowId(result.cashFlowId().id())
                .stagingSessionId(result.stagingSessionId().id())
                .status(result.status().name())
                .input(toImportInputJson(result.input()))
                .progress(toImportProgressJson(result.progress()))
                .result(toImportResultJson(result.result()))
                .canRollback(result.canRollback())
                .pollUrl(result.pollUrl())
                .build();
    }
    private BankDataIngestionDto.GetImportProgressResponse toGetImportProgressResponse(GetImportProgressResult result) {
        return BankDataIngestionDto.GetImportProgressResponse.builder()
                .jobId(result.jobId().id())
                .cashFlowId(result.cashFlowId().id())
                .status(result.status().name())
                .progress(toImportProgressJson(result.progress()))
                .result(toImportResultJson(result.result()))
                .summary(toImportSummaryJson(result.summary()))
                .canRollback(result.canRollback())
                .rollbackDeadline(result.rollbackDeadline())
                .elapsedTimeMs(result.elapsedTimeMs())
                .build();
    }
    private BankDataIngestionDto.RollbackImportResponse toRollbackImportResponse(RollbackImportJobResult result) {
        return BankDataIngestionDto.RollbackImportResponse.builder()
                .jobId(result.jobId().id())
                .status(result.status().name())
                .rollbackSummary(BankDataIngestionDto.RollbackSummaryJson.builder()
                        .transactionsDeleted(result.rollbackSummary().transactionsDeleted())
                        .categoriesDeleted(result.rollbackSummary().categoriesDeleted())
                        .rollbackDurationMs(result.rollbackSummary().rollbackDurationMs())
                        .build())
                .build();
    }
    private BankDataIngestionDto.FinalizeImportResponse toFinalizeImportResponse(FinalizeImportJobResult result) {
        return BankDataIngestionDto.FinalizeImportResponse.builder()
                .jobId(result.jobId().id())
                .status(result.status().name())
                .cleanup(BankDataIngestionDto.CleanupSummaryJson.builder()
                        .stagedTransactionsDeleted(result.cleanup().stagedTransactionsDeleted())
                        .mappingsDeleted(result.cleanup().mappingsDeleted())
                        .build())
                .finalSummary(BankDataIngestionDto.FinalSummaryJson.builder()
                        .importedAt(result.finalSummary().importedAt())
                        .totalDurationMs(result.finalSummary().totalDurationMs())
                        .categoriesCreated(result.finalSummary().categoriesCreated())
                        .transactionsImported(result.finalSummary().transactionsImported())
                        .categoryBreakdown(result.finalSummary().categoryBreakdown().stream()
                                .map(this::toImportCategoryBreakdownJson)
                                .toList())
                        .build())
                .build();
    }
    private BankDataIngestionDto.ListImportJobsResponse toListImportJobsResponse(ListImportJobsResult result) {
        return BankDataIngestionDto.ListImportJobsResponse.builder()
                .cashFlowId(result.cashFlowId().id())
                .jobs(result.jobs().stream()
                        .map(this::toImportJobSummaryJson)
                        .toList())
                .build();
    }
    private BankDataIngestionDto.ImportInputJson toImportInputJson(StartImportJobResult.InputSummary input) {
        return BankDataIngestionDto.ImportInputJson.builder()
                .totalTransactions(input.totalTransactions())
                .validTransactions(input.validTransactions())
                .duplicateTransactions(input.duplicateTransactions())
                .categoriesToCreate(input.categoriesToCreate())
                .build();
    }
    private BankDataIngestionDto.ImportProgressJson toImportProgressJson(ImportJob.ImportProgress progress) {
        if (progress == null) return null;

        return BankDataIngestionDto.ImportProgressJson.builder()
                .percentage(progress.percentage())
                .currentPhase(progress.currentPhase() != null ? progress.currentPhase().name() : null)
                .phases(progress.phases().stream()
                        .map(this::toPhaseProgressJson)
                        .toList())
                .build();
    }
    private BankDataIngestionDto.PhaseProgressJson toPhaseProgressJson(ImportJob.PhaseProgress phase) {
        return BankDataIngestionDto.PhaseProgressJson.builder()
                .name(phase.name().name())
                .status(phase.status().name())
                .processed(phase.processed())
                .total(phase.total())
                .startedAt(phase.startedAt())
                .completedAt(phase.completedAt())
                .durationMs(phase.durationMs())
                .build();
    }
    private BankDataIngestionDto.ImportResultJson toImportResultJson(ImportJob.ImportResult result) {
        if (result == null) return null;

        return BankDataIngestionDto.ImportResultJson.builder()
                .categoriesCreated(result.categoriesCreated())
                .transactionsImported(result.transactionsImported())
                .transactionsFailed(result.transactionsFailed())
                .errors(result.errors().stream()
                        .map(e -> BankDataIngestionDto.ImportErrorJson.builder()
                                .bankTransactionId(e.bankTransactionId())
                                .error(e.error())
                                .build())
                        .toList())
                .build();
    }
    private BankDataIngestionDto.ImportSummaryJson toImportSummaryJson(ImportJob.ImportSummary summary) {
        if (summary == null) return null;

        return BankDataIngestionDto.ImportSummaryJson.builder()
                .categoryBreakdown(summary.categoryBreakdown().stream()
                        .map(this::toImportCategoryBreakdownJson)
                        .toList())
                .monthlyBreakdown(summary.monthlyBreakdown().stream()
                        .map(this::toImportMonthlyBreakdownJson)
                        .toList())
                .totalDurationMs(summary.totalDurationMs())
                .build();
    }
    private BankDataIngestionDto.ImportCategoryBreakdownJson toImportCategoryBreakdownJson(
            ImportJob.CategoryBreakdown breakdown) {
        return BankDataIngestionDto.ImportCategoryBreakdownJson.builder()
                .categoryName(breakdown.categoryName())
                .parentCategory(breakdown.parentCategory())
                .transactionCount(breakdown.transactionCount())
                .totalAmount(breakdown.totalAmount().getAmount().doubleValue())
                .currency(breakdown.totalAmount().getCurrency())
                .type(breakdown.type())
                .isNewCategory(breakdown.isNewCategory())
                .build();
    }
    private BankDataIngestionDto.ImportMonthlyBreakdownJson toImportMonthlyBreakdownJson(
            ImportJob.MonthlyBreakdown breakdown) {
        return BankDataIngestionDto.ImportMonthlyBreakdownJson.builder()
                .month(breakdown.month())
                .inflowTotal(breakdown.inflowTotal().getAmount().doubleValue())
                .outflowTotal(breakdown.outflowTotal().getAmount().doubleValue())
                .currency(breakdown.inflowTotal().getCurrency())
                .transactionCount(breakdown.transactionCount())
                .build();
    }
    private BankDataIngestionDto.ImportJobSummaryJson toImportJobSummaryJson(
            ListImportJobsResult.ImportJobSummary summary) {
        return BankDataIngestionDto.ImportJobSummaryJson.builder()
                .jobId(summary.jobId().id())
                .status(summary.status().name())
                .createdAt(summary.createdAt())
                .completedAt(summary.completedAt())
                .transactionsImported(summary.transactionsImported())
                .categoriesCreated(summary.categoriesCreated())
                .canRollback(summary.canRollback())
                .build();
    }
}
