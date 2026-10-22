package com.multi.vidulum.bank_data_ingestion.staging;

import com.multi.vidulum.common.auth.AuthenticatedUserProvider;
import com.multi.vidulum.bank_data_ingestion.staging.commands.delete_staging_session.DeleteStagingSessionCommand;
import com.multi.vidulum.bank_data_ingestion.staging.commands.delete_staging_session.DeleteStagingSessionResult;
import com.multi.vidulum.bank_data_ingestion.staging.commands.force_uncategorized.ForceUncategorizedCommand;
import com.multi.vidulum.bank_data_ingestion.staging.commands.force_uncategorized.ForceUncategorizedResult;
import com.multi.vidulum.bank_data_ingestion.staging.commands.revalidate_staging.RevalidateStagingCommand;
import com.multi.vidulum.bank_data_ingestion.staging.commands.revalidate_staging.RevalidateStagingResult;
import com.multi.vidulum.bank_data_ingestion.staging.commands.stage_transactions.StageTransactionsCommand;
import com.multi.vidulum.bank_data_ingestion.staging.commands.stage_transactions.StageTransactionsResult;
import com.multi.vidulum.bank_data_ingestion.staging.commands.upload_csv.UploadCsvCommand;
import com.multi.vidulum.bank_data_ingestion.staging.commands.upload_csv.UploadCsvResult;
import com.multi.vidulum.bank_data_ingestion.staging.queries.get_staging_preview.GetStagingPreviewQuery;
import com.multi.vidulum.bank_data_ingestion.staging.queries.get_staging_preview.GetStagingPreviewResult;
import com.multi.vidulum.bank_data_ingestion.staging.queries.list_staging_sessions.ListStagingSessionsQuery;
import com.multi.vidulum.bank_data_ingestion.staging.queries.list_staging_sessions.ListStagingSessionsResult;
import com.multi.vidulum.bank_data_ingestion.canonical.*;
import com.multi.vidulum.bank_data_ingestion.staging.domain.*;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import java.util.List;
import lombok.AllArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * The staging session over HTTP: list it, create it, read it, delete it, revalidate it, force the
 * rest to Uncategorized - and upload a CSV, which lands here rather than in {@code intake} because
 * its outcome is a staging session and its response embeds staging's own result.

 * <p>Split out of the single 1,195-line controller. <b>The URL prefix is unchanged.</b>
 */
@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/bank-data-ingestion/cf={cashFlowId}")
public class StagingRestController {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;
    private final AuthenticatedUserProvider authenticatedUserProvider;

    /**
     * List all active (non-expired) staging sessions for a CashFlow.
     * Allows users to return to unfinished imports.
     */
    @GetMapping("/staging")
    public StagingDto.ListStagingSessionsResponse listStagingSessions(
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
    public StagingDto.StageTransactionsResponse stageTransactions(
            @PathVariable("cashFlowId") String cashFlowId,
            @RequestBody StagingDto.StageTransactionsRequest request) {

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
    public StagingDto.GetStagingPreviewResponse getStagingPreview(
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
    public StagingDto.DeleteStagingSessionResponse deleteStagingSession(
            @PathVariable("cashFlowId") String cashFlowId,
            @PathVariable("stagingSessionId") String stagingSessionId) {

        DeleteStagingSessionResult result = commandGateway.send(
                new DeleteStagingSessionCommand(
                        CashFlowId.of(cashFlowId),
                        StagingSessionId.of(stagingSessionId)
                )
        );

        return StagingDto.DeleteStagingSessionResponse.builder()
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
    public StagingDto.RevalidateStagingResponse revalidateStaging(
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
     *
     * Optional metadata fields can be passed from AI transformation:
     * - transformationId: ID of the AI transformation that created this CSV
     * - detectedLanguage: Language detected by AI (e.g., "pl", "en", "de")
     * - detectedBank: Bank detected by AI (e.g., "Nest Bank", "PKO BP")
     * - detectedCountry: Country detected from bank or IBAN (e.g., "PL", "DE")
     * - originalFileName: Original uploaded file name
     * - userId: User ID who created this session
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StagingDto.UploadCsvResponse uploadCsv(
            @PathVariable("cashFlowId") String cashFlowId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "transformationId", required = false) String transformationId,
            @RequestParam(value = "detectedLanguage", required = false) String detectedLanguage,
            @RequestParam(value = "detectedBank", required = false) String detectedBank,
            @RequestParam(value = "detectedCountry", required = false) String detectedCountry,
            @RequestParam(value = "originalFileName", required = false) String originalFileName,
            @RequestParam(value = "userId", required = false) String userId) {

        // Build metadata if any field is present
        UploadCsvCommand.SessionMetadata metadata = null;
        if (transformationId != null || detectedLanguage != null || detectedBank != null ||
                detectedCountry != null || originalFileName != null || userId != null) {
            // If no userId provided, try to get from security context
            String effectiveUserId = userId != null ? userId : getCurrentUserId();
            metadata = new UploadCsvCommand.SessionMetadata(
                    transformationId,
                    detectedLanguage,
                    detectedBank,
                    detectedCountry,
                    originalFileName != null ? originalFileName : file.getOriginalFilename(),
                    effectiveUserId
            );
        }

        UploadCsvResult result = commandGateway.send(
                new UploadCsvCommand(
                        CashFlowId.of(cashFlowId),
                        file,
                        metadata
                )
        );

        return toUploadCsvResponse(result);
    }
    /**
     * Force all PENDING_MAPPING transactions to use "Uncategorized" category.
     * This is an explicit user decision to proceed with import without categorizing.
     *
     * The "Uncategorized" category will be created automatically if it doesn't exist.
     * After this call, the staging session should be READY_FOR_IMPORT (unless there are other errors).
     */
    @PostMapping("/staging/{stagingSessionId}/force-uncategorized")
    public StagingDto.ForceUncategorizedResponse forceUncategorized(
            @PathVariable("cashFlowId") String cashFlowId,
            @PathVariable("stagingSessionId") String stagingSessionId) {

        ForceUncategorizedResult result = commandGateway.send(
                new ForceUncategorizedCommand(
                        CashFlowId.of(cashFlowId),
                        StagingSessionId.of(stagingSessionId)
                )
        );

        return toForceUncategorizedResponse(result);
    }
    private StagingDto.ForceUncategorizedResponse toForceUncategorizedResponse(
            ForceUncategorizedResult result) {
        return StagingDto.ForceUncategorizedResponse.builder()
                .cashFlowId(result.cashFlowId().id())
                .stagingSessionId(result.stagingSessionId().id())
                .status(result.status().name())
                .transactionsUpdated(result.transactionsUpdated())
                .categoryCreated(result.categoryCreated())
                .validationSummary(StagingDto.ForceUncategorizedValidationSummaryJson.builder()
                        .totalTransactions(result.validationSummary().totalTransactions())
                        .validTransactions(result.validationSummary().validTransactions())
                        .invalidTransactions(result.validationSummary().invalidTransactions())
                        .duplicateTransactions(result.validationSummary().duplicateTransactions())
                        .readyForImport(result.validationSummary().readyForImport())
                        .build())
                .build();
    }
    private StagingDto.UploadCsvResponse toUploadCsvResponse(UploadCsvResult result) {
        StagingDto.ParseSummaryJson parseSummary = StagingDto.ParseSummaryJson.builder()
                .totalRows(result.parseResult().totalRows())
                .successfulRows(result.parseResult().successfulRows())
                .failedRows(result.parseResult().failedRows())
                .errors(result.parseResult().errors().stream()
                        .map(e -> StagingDto.ParseErrorJson.builder()
                                .rowNumber(e.rowNumber())
                                .message(e.message())
                                .build())
                        .toList())
                .build();

        StagingDto.StageTransactionsResponse stagingResponse = null;
        if (result.stagingResult() != null) {
            stagingResponse = toStageTransactionsResponse(result.stagingResult());
        }

        return StagingDto.UploadCsvResponse.builder()
                .parseSummary(parseSummary)
                .stagingResult(stagingResponse)
                .build();
    }
    private StageTransactionsCommand.BankTransaction toBankTransaction(
            StagingDto.BankTransactionJson json) {
        return new StageTransactionsCommand.BankTransaction(
                json.getBankTransactionId(),
                json.getName(),
                json.getDescription(),
                json.getBankCategory(),
                Money.of(json.getAmount(), json.getCurrency()),
                json.getType(),
                json.getPaidDate(),
                json.getMerchant(),
                json.getMerchantConfidence(),
                json.getCounterpartyAccount(),
                json.getPaymentMethod(),
                json.getClassification()
        );
    }
    private StagingDto.StageTransactionsResponse toStageTransactionsResponse(
            StageTransactionsResult result) {

        return StagingDto.StageTransactionsResponse.builder()
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
    private StagingDto.GetStagingPreviewResponse toGetStagingPreviewResponse(
            GetStagingPreviewResult result) {

        return StagingDto.GetStagingPreviewResponse.builder()
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
    private StagingDto.StagingSummaryJson toStagingSummaryJson(
            StageTransactionsResult.StagingSummary summary) {
        return StagingDto.StagingSummaryJson.builder()
                .totalTransactions(summary.totalTransactions())
                .validTransactions(summary.validTransactions())
                .invalidTransactions(summary.invalidTransactions())
                .duplicateTransactions(summary.duplicateTransactions())
                .build();
    }
    private StagingDto.StagingSummaryJson toStagingSummaryJson(
            GetStagingPreviewResult.StagingSummary summary) {
        return StagingDto.StagingSummaryJson.builder()
                .totalTransactions(summary.totalTransactions())
                .validTransactions(summary.validTransactions())
                .invalidTransactions(summary.invalidTransactions())
                .duplicateTransactions(summary.duplicateTransactions())
                .build();
    }
    private StagingDto.CategoryBreakdownJson toCategoryBreakdownJson(
            StageTransactionsResult.CategoryBreakdown breakdown) {
        return StagingDto.CategoryBreakdownJson.builder()
                .targetCategory(breakdown.targetCategory())
                .parentCategory(breakdown.parentCategory())
                .transactionCount(breakdown.transactionCount())
                .totalAmount(breakdown.totalAmount().getAmount().doubleValue())
                .currency(breakdown.totalAmount().getCurrency())
                .type(breakdown.type())
                .isNewCategory(breakdown.isNewCategory())
                .build();
    }
    private StagingDto.CategoryBreakdownJson toCategoryBreakdownJsonFromPreview(
            GetStagingPreviewResult.CategoryBreakdown breakdown) {
        return StagingDto.CategoryBreakdownJson.builder()
                .targetCategory(breakdown.targetCategory())
                .parentCategory(breakdown.parentCategory())
                .transactionCount(breakdown.transactionCount())
                .totalAmount(breakdown.totalAmount().getAmount().doubleValue())
                .currency(breakdown.totalAmount().getCurrency())
                .type(breakdown.type())
                .isNewCategory(breakdown.isNewCategory())
                .build();
    }
    private StagingDto.CategoryToCreateJson toCategoryToCreateJson(
            StageTransactionsResult.CategoryToCreate category) {
        return StagingDto.CategoryToCreateJson.builder()
                .name(category.name())
                .parent(category.parent())
                .type(category.type())
                .build();
    }
    private StagingDto.CategoryToCreateJson toCategoryToCreateJsonFromPreview(
            GetStagingPreviewResult.CategoryToCreate category) {
        return StagingDto.CategoryToCreateJson.builder()
                .name(category.name())
                .parent(category.parent())
                .type(category.type())
                .build();
    }
    private StagingDto.MonthlyBreakdownJson toMonthlyBreakdownJson(
            StageTransactionsResult.MonthlyBreakdown breakdown) {
        return StagingDto.MonthlyBreakdownJson.builder()
                .month(breakdown.month())
                .inflowTotal(breakdown.inflowTotal().getAmount().doubleValue())
                .outflowTotal(breakdown.outflowTotal().getAmount().doubleValue())
                .currency(breakdown.inflowTotal().getCurrency())
                .transactionCount(breakdown.transactionCount())
                .build();
    }
    private StagingDto.MonthlyBreakdownJson toMonthlyBreakdownJsonFromPreview(
            GetStagingPreviewResult.MonthlyBreakdown breakdown) {
        return StagingDto.MonthlyBreakdownJson.builder()
                .month(breakdown.month())
                .inflowTotal(breakdown.inflowTotal().getAmount().doubleValue())
                .outflowTotal(breakdown.outflowTotal().getAmount().doubleValue())
                .currency(breakdown.inflowTotal().getCurrency())
                .transactionCount(breakdown.transactionCount())
                .build();
    }
    private StagingDto.DuplicateInfoJson toDuplicateInfoJson(
            StageTransactionsResult.DuplicateInfo duplicate) {
        return StagingDto.DuplicateInfoJson.builder()
                .bankTransactionId(duplicate.bankTransactionId())
                .name(duplicate.name())
                .duplicateOf(duplicate.duplicateOf())
                .build();
    }
    private StagingDto.UnmappedCategoryJson toUnmappedCategoryJson(
            StageTransactionsResult.UnmappedCategory unmapped) {
        return StagingDto.UnmappedCategoryJson.builder()
                .bankCategory(unmapped.bankCategory())
                .count(unmapped.count())
                .type(unmapped.type())
                .build();
    }
    private StagingDto.StagedTransactionPreviewJson toStagedTransactionPreviewJson(
            GetStagingPreviewResult.StagedTransactionPreview preview) {
        return StagingDto.StagedTransactionPreviewJson.builder()
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
                .validation(StagingDto.ValidationResultJson.builder()
                        .status(preview.validation().status())
                        .errors(preview.validation().errors())
                        .duplicateOf(preview.validation().duplicateOf())
                        .build())
                .build();
    }
    private StagingDto.ListStagingSessionsResponse toListStagingSessionsResponse(
            ListStagingSessionsResult result) {
        return StagingDto.ListStagingSessionsResponse.builder()
                .cashFlowId(result.cashFlowId().id())
                .stagingSessions(result.stagingSessions().stream()
                        .map(this::toStagingSessionSummaryJson)
                        .toList())
                .hasPendingImport(result.hasPendingImport())
                .build();
    }
    private StagingDto.RevalidateStagingResponse toRevalidateStagingResponse(
            RevalidateStagingResult result) {
        return StagingDto.RevalidateStagingResponse.builder()
                .stagingSessionId(result.stagingSessionId().id())
                .cashFlowId(result.cashFlowId().id())
                .status(result.status().name())
                .summary(StagingDto.RevalidationSummaryJson.builder()
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
    private StagingDto.StagingSessionSummaryJson toStagingSessionSummaryJson(
            ListStagingSessionsResult.StagingSessionSummary summary) {
        return StagingDto.StagingSessionSummaryJson.builder()
                .stagingSessionId(summary.stagingSessionId().id())
                .status(summary.status())
                .createdAt(summary.createdAt())
                .expiresAt(summary.expiresAt())
                .counts(StagingDto.StagingSessionCountsJson.builder()
                        .totalTransactions(summary.counts().totalTransactions())
                        .validTransactions(summary.counts().validTransactions())
                        .invalidTransactions(summary.counts().invalidTransactions())
                        .duplicateTransactions(summary.counts().duplicateTransactions())
                        .build())
                .build();
    }

    private String getCurrentUserId() {
        return authenticatedUserProvider.getCurrentUserId().getId();
    }
}
