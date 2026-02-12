package com.multi.vidulum.bank_data_ingestion.app;

import com.multi.vidulum.bank_data_ingestion.app.commands.configure_mapping.ConfigureCategoryMappingCommand;
import com.multi.vidulum.bank_data_ingestion.app.commands.configure_mapping.ConfigureCategoryMappingResult;
import com.multi.vidulum.bank_data_ingestion.app.commands.delete_all_mappings.DeleteAllCategoryMappingsCommand;
import com.multi.vidulum.bank_data_ingestion.app.commands.delete_all_mappings.DeleteAllCategoryMappingsResult;
import com.multi.vidulum.bank_data_ingestion.app.commands.delete_mapping.DeleteCategoryMappingCommand;
import com.multi.vidulum.bank_data_ingestion.app.commands.delete_mapping.DeleteCategoryMappingResult;
import com.multi.vidulum.bank_data_ingestion.app.commands.delete_staging_session.DeleteStagingSessionCommand;
import com.multi.vidulum.bank_data_ingestion.app.commands.delete_staging_session.DeleteStagingSessionResult;
import com.multi.vidulum.bank_data_ingestion.app.commands.finalize_import.FinalizeImportJobCommand;
import com.multi.vidulum.bank_data_ingestion.app.commands.finalize_import.FinalizeImportJobResult;
import com.multi.vidulum.bank_data_ingestion.app.commands.revalidate_staging.RevalidateStagingCommand;
import com.multi.vidulum.bank_data_ingestion.app.commands.revalidate_staging.RevalidateStagingResult;
import com.multi.vidulum.bank_data_ingestion.app.commands.rollback_import.RollbackImportJobCommand;
import com.multi.vidulum.bank_data_ingestion.app.commands.rollback_import.RollbackImportJobResult;
import com.multi.vidulum.bank_data_ingestion.app.commands.stage_transactions.StageTransactionsCommand;
import com.multi.vidulum.bank_data_ingestion.app.commands.stage_transactions.StageTransactionsResult;
import com.multi.vidulum.bank_data_ingestion.app.commands.start_import.StartImportJobCommand;
import com.multi.vidulum.bank_data_ingestion.app.commands.start_import.StartImportJobResult;
import com.multi.vidulum.bank_data_ingestion.app.commands.upload_csv.UploadCsvCommand;
import com.multi.vidulum.bank_data_ingestion.app.commands.upload_csv.UploadCsvResult;
import com.multi.vidulum.bank_data_ingestion.app.queries.get_import_progress.GetImportProgressQuery;
import com.multi.vidulum.bank_data_ingestion.app.queries.get_import_progress.GetImportProgressResult;
import com.multi.vidulum.bank_data_ingestion.app.queries.get_mappings.GetCategoryMappingsQuery;
import com.multi.vidulum.bank_data_ingestion.app.queries.get_mappings.GetCategoryMappingsResult;
import com.multi.vidulum.bank_data_ingestion.app.queries.get_staging_preview.GetStagingPreviewQuery;
import com.multi.vidulum.bank_data_ingestion.app.queries.get_staging_preview.GetStagingPreviewResult;
import com.multi.vidulum.bank_data_ingestion.app.queries.list_import_jobs.ListImportJobsQuery;
import com.multi.vidulum.bank_data_ingestion.app.queries.list_import_jobs.ListImportJobsResult;
import com.multi.vidulum.bank_data_ingestion.app.queries.list_staging_sessions.ListStagingSessionsQuery;
import com.multi.vidulum.bank_data_ingestion.app.queries.list_staging_sessions.ListStagingSessionsResult;
import com.multi.vidulum.bank_data_ingestion.domain.*;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * REST controller for Bank Data Ingestion API.
 * Handles category mappings and transaction staging for bank data import.
 */
@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/bank-data-ingestion/cf={cashFlowId}")
public class BankDataIngestionRestController {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;

    /**
     * Configure category mappings for bank data ingestion.
     * This replaces existing mappings for the same (bankCategoryName, categoryType) combination.
     */
    @PostMapping("/mappings")
    public BankDataIngestionDto.ConfigureMappingsResponse configureMappings(
            @PathVariable("cashFlowId") String cashFlowId,
            @RequestBody BankDataIngestionDto.ConfigureMappingsRequest request) {

        List<ConfigureCategoryMappingCommand.MappingConfig> mappingConfigs = request.getMappings().stream()
                .map(this::toMappingConfig)
                .toList();

        ConfigureCategoryMappingResult result = commandGateway.send(
                new ConfigureCategoryMappingCommand(
                        CashFlowId.of(cashFlowId),
                        mappingConfigs
                )
        );

        return toConfigureMappingsResponse(result);
    }

    /**
     * Get all category mappings for a CashFlow.
     */
    @GetMapping("/mappings")
    public BankDataIngestionDto.GetMappingsResponse getMappings(
            @PathVariable("cashFlowId") String cashFlowId) {

        GetCategoryMappingsResult result = queryGateway.send(
                new GetCategoryMappingsQuery(CashFlowId.of(cashFlowId))
        );

        return toGetMappingsResponse(result);
    }

    /**
     * Delete a single category mapping.
     */
    @DeleteMapping("/mappings/{mappingId}")
    public BankDataIngestionDto.DeleteMappingResponse deleteMapping(
            @PathVariable("cashFlowId") String cashFlowId,
            @PathVariable("mappingId") String mappingId) {

        DeleteCategoryMappingResult result = commandGateway.send(
                new DeleteCategoryMappingCommand(
                        CashFlowId.of(cashFlowId),
                        MappingId.of(mappingId)
                )
        );

        return BankDataIngestionDto.DeleteMappingResponse.builder()
                .deleted(result.deleted())
                .mappingId(result.mappingId().id())
                .bankCategoryName(result.bankCategoryName())
                .build();
    }

    /**
     * Delete all category mappings for a CashFlow.
     */
    @DeleteMapping("/mappings")
    public BankDataIngestionDto.DeleteAllMappingsResponse deleteAllMappings(
            @PathVariable("cashFlowId") String cashFlowId) {

        DeleteAllCategoryMappingsResult result = commandGateway.send(
                new DeleteAllCategoryMappingsCommand(CashFlowId.of(cashFlowId))
        );

        return BankDataIngestionDto.DeleteAllMappingsResponse.builder()
                .deleted(result.deleted())
                .deletedCount(result.deletedCount())
                .build();
    }

    // ============ Mapping helpers ============

    private ConfigureCategoryMappingCommand.MappingConfig toMappingConfig(
            BankDataIngestionDto.MappingConfigJson json) {

        CategoryName targetCategoryName = json.getAction() == MappingAction.MAP_TO_UNCATEGORIZED
                ? new CategoryName("Uncategorized")
                : new CategoryName(json.getTargetCategoryName());

        CategoryName parentCategoryName = json.getParentCategoryName() != null
                ? new CategoryName(json.getParentCategoryName())
                : null;

        return new ConfigureCategoryMappingCommand.MappingConfig(
                json.getBankCategoryName(),
                targetCategoryName,
                parentCategoryName,
                json.getCategoryType(),
                json.getAction()
        );
    }

    private BankDataIngestionDto.ConfigureMappingsResponse toConfigureMappingsResponse(
            ConfigureCategoryMappingResult result) {

        List<BankDataIngestionDto.MappingResultJson> mappingResults = result.configuredMappings().stream()
                .map(r -> BankDataIngestionDto.MappingResultJson.builder()
                        .mappingId(r.mapping().mappingId().id())
                        .bankCategoryName(r.mapping().bankCategoryName())
                        .targetCategoryName(r.mapping().targetCategoryName().name())
                        .parentCategoryName(r.mapping().parentCategoryName() != null
                                ? r.mapping().parentCategoryName().name() : null)
                        .categoryType(r.mapping().categoryType())
                        .action(r.mapping().action())
                        .status(r.status().name())
                        .build())
                .toList();

        return BankDataIngestionDto.ConfigureMappingsResponse.builder()
                .cashFlowId(result.cashFlowId().id())
                .mappingsConfigured(result.mappingsConfigured())
                .mappings(mappingResults)
                .build();
    }

    private BankDataIngestionDto.GetMappingsResponse toGetMappingsResponse(
            GetCategoryMappingsResult result) {

        List<BankDataIngestionDto.MappingJson> mappings = result.mappings().stream()
                .map(this::toMappingJson)
                .toList();

        return BankDataIngestionDto.GetMappingsResponse.builder()
                .cashFlowId(result.cashFlowId().id())
                .mappingsCount(result.mappingsCount())
                .mappings(mappings)
                .build();
    }

    private BankDataIngestionDto.MappingJson toMappingJson(CategoryMapping mapping) {
        return BankDataIngestionDto.MappingJson.builder()
                .mappingId(mapping.mappingId().id())
                .bankCategoryName(mapping.bankCategoryName())
                .targetCategoryName(mapping.targetCategoryName().name())
                .parentCategoryName(mapping.parentCategoryName() != null
                        ? mapping.parentCategoryName().name() : null)
                .categoryType(mapping.categoryType())
                .action(mapping.action())
                .createdAt(mapping.createdAt())
                .updatedAt(mapping.updatedAt())
                .build();
    }

    // ============ Staging endpoints ============

    /**
     * List all active (non-expired) staging sessions for a CashFlow.
     * Allows users to return to unfinished imports.
     */
    @GetMapping("/staging")
    public BankDataIngestionDto.ListStagingSessionsResponse listStagingSessions(
            @PathVariable("cashFlowId") String cashFlowId) {

        ListStagingSessionsResult result = queryGateway.send(
                new ListStagingSessionsQuery(CashFlowId.of(cashFlowId))
        );

        return toListStagingSessionsResponse(result);
    }

    /**
     * Stage bank transactions for import preview.
     * Validates transactions, applies category mappings, and returns a staging preview.
     */
    @PostMapping("/staging")
    public BankDataIngestionDto.StageTransactionsResponse stageTransactions(
            @PathVariable("cashFlowId") String cashFlowId,
            @RequestBody BankDataIngestionDto.StageTransactionsRequest request) {

        List<StageTransactionsCommand.BankTransaction> transactions = request.getTransactions().stream()
                .map(this::toBankTransaction)
                .toList();

        StageTransactionsResult result = commandGateway.send(
                new StageTransactionsCommand(
                        CashFlowId.of(cashFlowId),
                        transactions
                )
        );

        return toStageTransactionsResponse(result);
    }

    /**
     * Get a preview of staged transactions for a staging session.
     */
    @GetMapping("/staging/{stagingSessionId}")
    public BankDataIngestionDto.GetStagingPreviewResponse getStagingPreview(
            @PathVariable("cashFlowId") String cashFlowId,
            @PathVariable("stagingSessionId") String stagingSessionId) {

        GetStagingPreviewResult result = queryGateway.send(
                new GetStagingPreviewQuery(
                        CashFlowId.of(cashFlowId),
                        StagingSessionId.of(stagingSessionId)
                )
        );

        return toGetStagingPreviewResponse(result);
    }

    /**
     * Delete a staging session and all its staged transactions.
     */
    @DeleteMapping("/staging/{stagingSessionId}")
    public BankDataIngestionDto.DeleteStagingSessionResponse deleteStagingSession(
            @PathVariable("cashFlowId") String cashFlowId,
            @PathVariable("stagingSessionId") String stagingSessionId) {

        DeleteStagingSessionResult result = commandGateway.send(
                new DeleteStagingSessionCommand(
                        CashFlowId.of(cashFlowId),
                        StagingSessionId.of(stagingSessionId)
                )
        );

        return BankDataIngestionDto.DeleteStagingSessionResponse.builder()
                .cashFlowId(result.cashFlowId().id())
                .stagingSessionId(result.stagingSessionId().id())
                .deleted(result.deleted())
                .deletedCount(result.deletedCount())
                .build();
    }

    /**
     * Revalidate a staging session after category mappings have been configured.
     * Updates transactions that were PENDING_MAPPING to have proper mapped data.
     */
    @PostMapping("/staging/{stagingSessionId}/revalidate")
    public BankDataIngestionDto.RevalidateStagingResponse revalidateStaging(
            @PathVariable("cashFlowId") String cashFlowId,
            @PathVariable("stagingSessionId") String stagingSessionId) {

        RevalidateStagingResult result = commandGateway.send(
                new RevalidateStagingCommand(
                        CashFlowId.of(cashFlowId),
                        StagingSessionId.of(stagingSessionId)
                )
        );

        return toRevalidateStagingResponse(result);
    }

    /**
     * Upload a CSV file with bank transactions in BankCsvRow format.
     * Parses the CSV and stages transactions for import.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BankDataIngestionDto.UploadCsvResponse uploadCsv(
            @PathVariable("cashFlowId") String cashFlowId,
            @RequestParam("file") MultipartFile file) {

        UploadCsvResult result = commandGateway.send(
                new UploadCsvCommand(
                        CashFlowId.of(cashFlowId),
                        file
                )
        );

        return toUploadCsvResponse(result);
    }

    // ============ Upload CSV mapping helpers ============

    private BankDataIngestionDto.UploadCsvResponse toUploadCsvResponse(UploadCsvResult result) {
        BankDataIngestionDto.ParseSummaryJson parseSummary = BankDataIngestionDto.ParseSummaryJson.builder()
                .totalRows(result.parseResult().totalRows())
                .successfulRows(result.parseResult().successfulRows())
                .failedRows(result.parseResult().failedRows())
                .errors(result.parseResult().errors().stream()
                        .map(e -> BankDataIngestionDto.ParseErrorJson.builder()
                                .rowNumber(e.rowNumber())
                                .message(e.message())
                                .build())
                        .toList())
                .build();

        BankDataIngestionDto.StageTransactionsResponse stagingResponse = null;
        if (result.stagingResult() != null) {
            stagingResponse = toStageTransactionsResponse(result.stagingResult());
        }

        return BankDataIngestionDto.UploadCsvResponse.builder()
                .parseSummary(parseSummary)
                .stagingResult(stagingResponse)
                .build();
    }

    // ============ Staging mapping helpers ============

    private StageTransactionsCommand.BankTransaction toBankTransaction(
            BankDataIngestionDto.BankTransactionJson json) {
        return new StageTransactionsCommand.BankTransaction(
                json.getBankTransactionId(),
                json.getName(),
                json.getDescription(),
                json.getBankCategory(),
                Money.of(json.getAmount(), json.getCurrency()),
                json.getType(),
                json.getPaidDate()
        );
    }

    private BankDataIngestionDto.StageTransactionsResponse toStageTransactionsResponse(
            StageTransactionsResult result) {

        return BankDataIngestionDto.StageTransactionsResponse.builder()
                .stagingSessionId(result.stagingSessionId().id())
                .cashFlowId(result.cashFlowId().id())
                .status(result.status().name())
                .expiresAt(result.expiresAt())
                .summary(toStagingSummaryJson(result.summary()))
                .categoryBreakdown(result.categoryBreakdown().stream()
                        .map(this::toCategoryBreakdownJson)
                        .toList())
                .categoriesToCreate(result.categoriesToCreate().stream()
                        .map(this::toCategoryToCreateJson)
                        .toList())
                .monthlyBreakdown(result.monthlyBreakdown().stream()
                        .map(this::toMonthlyBreakdownJson)
                        .toList())
                .duplicates(result.duplicates().stream()
                        .map(this::toDuplicateInfoJson)
                        .toList())
                .unmappedCategories(result.unmappedCategories().stream()
                        .map(this::toUnmappedCategoryJson)
                        .toList())
                .build();
    }

    private BankDataIngestionDto.GetStagingPreviewResponse toGetStagingPreviewResponse(
            GetStagingPreviewResult result) {

        return BankDataIngestionDto.GetStagingPreviewResponse.builder()
                .stagingSessionId(result.stagingSessionId().id())
                .cashFlowId(result.cashFlowId().id())
                .status(result.status().name())
                .expiresAt(result.expiresAt())
                .summary(toStagingSummaryJson(result.summary()))
                .transactions(result.transactions().stream()
                        .map(this::toStagedTransactionPreviewJson)
                        .toList())
                .categoryBreakdown(result.categoryBreakdown().stream()
                        .map(this::toCategoryBreakdownJsonFromPreview)
                        .toList())
                .categoriesToCreate(result.categoriesToCreate().stream()
                        .map(this::toCategoryToCreateJsonFromPreview)
                        .toList())
                .monthlyBreakdown(result.monthlyBreakdown().stream()
                        .map(this::toMonthlyBreakdownJsonFromPreview)
                        .toList())
                .build();
    }

    private BankDataIngestionDto.StagingSummaryJson toStagingSummaryJson(
            StageTransactionsResult.StagingSummary summary) {
        return BankDataIngestionDto.StagingSummaryJson.builder()
                .totalTransactions(summary.totalTransactions())
                .validTransactions(summary.validTransactions())
                .invalidTransactions(summary.invalidTransactions())
                .duplicateTransactions(summary.duplicateTransactions())
                .build();
    }

    private BankDataIngestionDto.StagingSummaryJson toStagingSummaryJson(
            GetStagingPreviewResult.StagingSummary summary) {
        return BankDataIngestionDto.StagingSummaryJson.builder()
                .totalTransactions(summary.totalTransactions())
                .validTransactions(summary.validTransactions())
                .invalidTransactions(summary.invalidTransactions())
                .duplicateTransactions(summary.duplicateTransactions())
                .build();
    }

    private BankDataIngestionDto.CategoryBreakdownJson toCategoryBreakdownJson(
            StageTransactionsResult.CategoryBreakdown breakdown) {
        return BankDataIngestionDto.CategoryBreakdownJson.builder()
                .targetCategory(breakdown.targetCategory())
                .parentCategory(breakdown.parentCategory())
                .transactionCount(breakdown.transactionCount())
                .totalAmount(breakdown.totalAmount().getAmount().doubleValue())
                .currency(breakdown.totalAmount().getCurrency())
                .type(breakdown.type())
                .isNewCategory(breakdown.isNewCategory())
                .build();
    }

    private BankDataIngestionDto.CategoryBreakdownJson toCategoryBreakdownJsonFromPreview(
            GetStagingPreviewResult.CategoryBreakdown breakdown) {
        return BankDataIngestionDto.CategoryBreakdownJson.builder()
                .targetCategory(breakdown.targetCategory())
                .parentCategory(breakdown.parentCategory())
                .transactionCount(breakdown.transactionCount())
                .totalAmount(breakdown.totalAmount().getAmount().doubleValue())
                .currency(breakdown.totalAmount().getCurrency())
                .type(breakdown.type())
                .isNewCategory(breakdown.isNewCategory())
                .build();
    }

    private BankDataIngestionDto.CategoryToCreateJson toCategoryToCreateJson(
            StageTransactionsResult.CategoryToCreate category) {
        return BankDataIngestionDto.CategoryToCreateJson.builder()
                .name(category.name())
                .parent(category.parent())
                .type(category.type())
                .build();
    }

    private BankDataIngestionDto.CategoryToCreateJson toCategoryToCreateJsonFromPreview(
            GetStagingPreviewResult.CategoryToCreate category) {
        return BankDataIngestionDto.CategoryToCreateJson.builder()
                .name(category.name())
                .parent(category.parent())
                .type(category.type())
                .build();
    }

    private BankDataIngestionDto.MonthlyBreakdownJson toMonthlyBreakdownJson(
            StageTransactionsResult.MonthlyBreakdown breakdown) {
        return BankDataIngestionDto.MonthlyBreakdownJson.builder()
                .month(breakdown.month())
                .inflowTotal(breakdown.inflowTotal().getAmount().doubleValue())
                .outflowTotal(breakdown.outflowTotal().getAmount().doubleValue())
                .currency(breakdown.inflowTotal().getCurrency())
                .transactionCount(breakdown.transactionCount())
                .build();
    }

    private BankDataIngestionDto.MonthlyBreakdownJson toMonthlyBreakdownJsonFromPreview(
            GetStagingPreviewResult.MonthlyBreakdown breakdown) {
        return BankDataIngestionDto.MonthlyBreakdownJson.builder()
                .month(breakdown.month())
                .inflowTotal(breakdown.inflowTotal().getAmount().doubleValue())
                .outflowTotal(breakdown.outflowTotal().getAmount().doubleValue())
                .currency(breakdown.inflowTotal().getCurrency())
                .transactionCount(breakdown.transactionCount())
                .build();
    }

    private BankDataIngestionDto.DuplicateInfoJson toDuplicateInfoJson(
            StageTransactionsResult.DuplicateInfo duplicate) {
        return BankDataIngestionDto.DuplicateInfoJson.builder()
                .bankTransactionId(duplicate.bankTransactionId())
                .name(duplicate.name())
                .duplicateOf(duplicate.duplicateOf())
                .build();
    }

    private BankDataIngestionDto.UnmappedCategoryJson toUnmappedCategoryJson(
            StageTransactionsResult.UnmappedCategory unmapped) {
        return BankDataIngestionDto.UnmappedCategoryJson.builder()
                .bankCategory(unmapped.bankCategory())
                .count(unmapped.count())
                .type(unmapped.type())
                .build();
    }

    private BankDataIngestionDto.StagedTransactionPreviewJson toStagedTransactionPreviewJson(
            GetStagingPreviewResult.StagedTransactionPreview preview) {
        return BankDataIngestionDto.StagedTransactionPreviewJson.builder()
                .stagedTransactionId(preview.stagedTransactionId())
                .bankTransactionId(preview.bankTransactionId())
                .name(preview.name())
                .description(preview.description())
                .bankCategory(preview.bankCategory())
                .targetCategory(preview.targetCategory())
                .parentCategory(preview.parentCategory())
                .amount(preview.amount().getAmount().doubleValue())
                .currency(preview.amount().getCurrency())
                .type(preview.type())
                .paidDate(preview.paidDate())
                .validation(BankDataIngestionDto.ValidationResultJson.builder()
                        .status(preview.validation().status())
                        .errors(preview.validation().errors())
                        .duplicateOf(preview.validation().duplicateOf())
                        .build())
                .build();
    }

    private BankDataIngestionDto.ListStagingSessionsResponse toListStagingSessionsResponse(
            ListStagingSessionsResult result) {
        return BankDataIngestionDto.ListStagingSessionsResponse.builder()
                .cashFlowId(result.cashFlowId().id())
                .stagingSessions(result.stagingSessions().stream()
                        .map(this::toStagingSessionSummaryJson)
                        .toList())
                .hasPendingImport(result.hasPendingImport())
                .build();
    }

    private BankDataIngestionDto.RevalidateStagingResponse toRevalidateStagingResponse(
            RevalidateStagingResult result) {
        return BankDataIngestionDto.RevalidateStagingResponse.builder()
                .stagingSessionId(result.stagingSessionId().id())
                .cashFlowId(result.cashFlowId().id())
                .status(result.status().name())
                .summary(BankDataIngestionDto.RevalidationSummaryJson.builder()
                        .totalTransactions(result.summary().totalTransactions())
                        .revalidatedCount(result.summary().revalidatedCount())
                        .stillPendingCount(result.summary().stillPendingCount())
                        .validCount(result.summary().validCount())
                        .invalidCount(result.summary().invalidCount())
                        .duplicateCount(result.summary().duplicateCount())
                        .build())
                .stillUnmappedCategories(result.stillUnmappedCategories())
                .build();
    }

    private BankDataIngestionDto.StagingSessionSummaryJson toStagingSessionSummaryJson(
            ListStagingSessionsResult.StagingSessionSummary summary) {
        return BankDataIngestionDto.StagingSessionSummaryJson.builder()
                .stagingSessionId(summary.stagingSessionId().id())
                .status(summary.status())
                .createdAt(summary.createdAt())
                .expiresAt(summary.expiresAt())
                .counts(BankDataIngestionDto.StagingSessionCountsJson.builder()
                        .totalTransactions(summary.counts().totalTransactions())
                        .validTransactions(summary.counts().validTransactions())
                        .invalidTransactions(summary.counts().invalidTransactions())
                        .duplicateTransactions(summary.counts().duplicateTransactions())
                        .build())
                .build();
    }

    // ============ Import Job endpoints ============

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

    // ============ Import Job mapping helpers ============

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
