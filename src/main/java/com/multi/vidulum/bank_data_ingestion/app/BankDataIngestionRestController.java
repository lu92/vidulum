package com.multi.vidulum.bank_data_ingestion.app;

import com.multi.vidulum.bank_data_ingestion.app.commands.configure_mapping.ConfigureCategoryMappingCommand;
import com.multi.vidulum.bank_data_ingestion.app.commands.configure_mapping.ConfigureCategoryMappingResult;
import com.multi.vidulum.bank_data_ingestion.app.commands.delete_all_mappings.DeleteAllCategoryMappingsCommand;
import com.multi.vidulum.bank_data_ingestion.app.commands.delete_all_mappings.DeleteAllCategoryMappingsResult;
import com.multi.vidulum.bank_data_ingestion.app.commands.delete_mapping.DeleteCategoryMappingCommand;
import com.multi.vidulum.bank_data_ingestion.app.commands.delete_mapping.DeleteCategoryMappingResult;
import com.multi.vidulum.bank_data_ingestion.app.commands.delete_staging_session.DeleteStagingSessionCommand;
import com.multi.vidulum.bank_data_ingestion.app.commands.delete_staging_session.DeleteStagingSessionResult;
import com.multi.vidulum.bank_data_ingestion.app.commands.stage_transactions.StageTransactionsCommand;
import com.multi.vidulum.bank_data_ingestion.app.commands.stage_transactions.StageTransactionsResult;
import com.multi.vidulum.bank_data_ingestion.app.queries.get_mappings.GetCategoryMappingsQuery;
import com.multi.vidulum.bank_data_ingestion.app.queries.get_mappings.GetCategoryMappingsResult;
import com.multi.vidulum.bank_data_ingestion.app.queries.get_staging_preview.GetStagingPreviewQuery;
import com.multi.vidulum.bank_data_ingestion.app.queries.get_staging_preview.GetStagingPreviewResult;
import com.multi.vidulum.bank_data_ingestion.domain.CategoryMapping;
import com.multi.vidulum.bank_data_ingestion.domain.MappingAction;
import com.multi.vidulum.bank_data_ingestion.domain.MappingId;
import com.multi.vidulum.bank_data_ingestion.domain.StagingSessionId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for Bank Data Ingestion API.
 * Handles category mappings and transaction staging for bank data import.
 */
@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/bank-data-ingestion/{cashFlowId}")
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
                        new CashFlowId(cashFlowId),
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
                new GetCategoryMappingsQuery(new CashFlowId(cashFlowId))
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
                        new CashFlowId(cashFlowId),
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
                new DeleteAllCategoryMappingsCommand(new CashFlowId(cashFlowId))
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
                        new CashFlowId(cashFlowId),
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
                        new CashFlowId(cashFlowId),
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
                        new CashFlowId(cashFlowId),
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
}
