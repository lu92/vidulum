package com.multi.vidulum.bank_data_ingestion.importing;

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
import com.multi.vidulum.bank_data_ingestion.staging.domain.*;
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
    public ImportDto.StartImportResponse startImport(
            @PathVariable("cashFlowId") String cashFlowId,
            @RequestBody ImportDto.StartImportRequest request) {

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
    public ImportDto.GetImportProgressResponse getImportProgress(
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
    public ImportDto.RollbackImportResponse rollbackImport(
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
    public ImportDto.FinalizeImportResponse finalizeImport(
            @PathVariable("cashFlowId") String cashFlowId,
            @PathVariable("jobId") String jobId,
            @RequestBody ImportDto.FinalizeImportRequest request) {

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
    public ImportDto.ListImportJobsResponse listImportJobs(
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
    private ImportDto.StartImportResponse toStartImportResponse(StartImportJobResult result) {
        return ImportDto.StartImportResponse.builder()
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
    private ImportDto.GetImportProgressResponse toGetImportProgressResponse(GetImportProgressResult result) {
        return ImportDto.GetImportProgressResponse.builder()
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
    private ImportDto.RollbackImportResponse toRollbackImportResponse(RollbackImportJobResult result) {
        return ImportDto.RollbackImportResponse.builder()
                .jobId(result.jobId().id())
                .status(result.status().name())
                .rollbackSummary(ImportDto.RollbackSummaryJson.builder()
                        .transactionsDeleted(result.rollbackSummary().transactionsDeleted())
                        .categoriesDeleted(result.rollbackSummary().categoriesDeleted())
                        .rollbackDurationMs(result.rollbackSummary().rollbackDurationMs())
                        .build())
                .build();
    }
    private ImportDto.FinalizeImportResponse toFinalizeImportResponse(FinalizeImportJobResult result) {
        return ImportDto.FinalizeImportResponse.builder()
                .jobId(result.jobId().id())
                .status(result.status().name())
                .cleanup(ImportDto.CleanupSummaryJson.builder()
                        .stagedTransactionsDeleted(result.cleanup().stagedTransactionsDeleted())
                        .mappingsDeleted(result.cleanup().mappingsDeleted())
                        .build())
                .finalSummary(ImportDto.FinalSummaryJson.builder()
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
    private ImportDto.ListImportJobsResponse toListImportJobsResponse(ListImportJobsResult result) {
        return ImportDto.ListImportJobsResponse.builder()
                .cashFlowId(result.cashFlowId().id())
                .jobs(result.jobs().stream()
                        .map(this::toImportJobSummaryJson)
                        .toList())
                .build();
    }
    private ImportDto.ImportInputJson toImportInputJson(StartImportJobResult.InputSummary input) {
        return ImportDto.ImportInputJson.builder()
                .totalTransactions(input.totalTransactions())
                .validTransactions(input.validTransactions())
                .duplicateTransactions(input.duplicateTransactions())
                .categoriesToCreate(input.categoriesToCreate())
                .build();
    }
    private ImportDto.ImportProgressJson toImportProgressJson(ImportJob.ImportProgress progress) {
        if (progress == null) return null;

        return ImportDto.ImportProgressJson.builder()
                .percentage(progress.percentage())
                .currentPhase(progress.currentPhase() != null ? progress.currentPhase().name() : null)
                .phases(progress.phases().stream()
                        .map(this::toPhaseProgressJson)
                        .toList())
                .build();
    }
    private ImportDto.PhaseProgressJson toPhaseProgressJson(ImportJob.PhaseProgress phase) {
        return ImportDto.PhaseProgressJson.builder()
                .name(phase.name().name())
                .status(phase.status().name())
                .processed(phase.processed())
                .total(phase.total())
                .startedAt(phase.startedAt())
                .completedAt(phase.completedAt())
                .durationMs(phase.durationMs())
                .build();
    }
    private ImportDto.ImportResultJson toImportResultJson(ImportJob.ImportResult result) {
        if (result == null) return null;

        return ImportDto.ImportResultJson.builder()
                .categoriesCreated(result.categoriesCreated())
                .transactionsImported(result.transactionsImported())
                .transactionsFailed(result.transactionsFailed())
                .errors(result.errors().stream()
                        .map(e -> ImportDto.ImportErrorJson.builder()
                                .bankTransactionId(e.bankTransactionId())
                                .error(e.error())
                                .build())
                        .toList())
                .build();
    }
    private ImportDto.ImportSummaryJson toImportSummaryJson(ImportJob.ImportSummary summary) {
        if (summary == null) return null;

        return ImportDto.ImportSummaryJson.builder()
                .categoryBreakdown(summary.categoryBreakdown().stream()
                        .map(this::toImportCategoryBreakdownJson)
                        .toList())
                .monthlyBreakdown(summary.monthlyBreakdown().stream()
                        .map(this::toImportMonthlyBreakdownJson)
                        .toList())
                .totalDurationMs(summary.totalDurationMs())
                .build();
    }
    private ImportDto.ImportCategoryBreakdownJson toImportCategoryBreakdownJson(
            ImportJob.CategoryBreakdown breakdown) {
        return ImportDto.ImportCategoryBreakdownJson.builder()
                .categoryName(breakdown.categoryName())
                .parentCategory(breakdown.parentCategory())
                .transactionCount(breakdown.transactionCount())
                .totalAmount(breakdown.totalAmount().getAmount().doubleValue())
                .currency(breakdown.totalAmount().getCurrency())
                .type(breakdown.type())
                .isNewCategory(breakdown.isNewCategory())
                .build();
    }
    private ImportDto.ImportMonthlyBreakdownJson toImportMonthlyBreakdownJson(
            ImportJob.MonthlyBreakdown breakdown) {
        return ImportDto.ImportMonthlyBreakdownJson.builder()
                .month(breakdown.month())
                .inflowTotal(breakdown.inflowTotal().getAmount().doubleValue())
                .outflowTotal(breakdown.outflowTotal().getAmount().doubleValue())
                .currency(breakdown.inflowTotal().getCurrency())
                .transactionCount(breakdown.transactionCount())
                .build();
    }
    private ImportDto.ImportJobSummaryJson toImportJobSummaryJson(
            ListImportJobsResult.ImportJobSummary summary) {
        return ImportDto.ImportJobSummaryJson.builder()
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
