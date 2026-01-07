package com.multi.vidulum.bank_data_ingestion.app;

import com.multi.vidulum.bank_data_ingestion.app.commands.configure_mapping.ConfigureCategoryMappingCommand;
import com.multi.vidulum.bank_data_ingestion.app.commands.configure_mapping.ConfigureCategoryMappingResult;
import com.multi.vidulum.bank_data_ingestion.app.commands.delete_all_mappings.DeleteAllCategoryMappingsCommand;
import com.multi.vidulum.bank_data_ingestion.app.commands.delete_all_mappings.DeleteAllCategoryMappingsResult;
import com.multi.vidulum.bank_data_ingestion.app.commands.delete_mapping.DeleteCategoryMappingCommand;
import com.multi.vidulum.bank_data_ingestion.app.commands.delete_mapping.DeleteCategoryMappingResult;
import com.multi.vidulum.bank_data_ingestion.app.queries.get_mappings.GetCategoryMappingsQuery;
import com.multi.vidulum.bank_data_ingestion.app.queries.get_mappings.GetCategoryMappingsResult;
import com.multi.vidulum.bank_data_ingestion.domain.CategoryMapping;
import com.multi.vidulum.bank_data_ingestion.domain.MappingAction;
import com.multi.vidulum.bank_data_ingestion.domain.MappingId;
import com.multi.vidulum.cashflow.domain.CashFlowId;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for Bank Data Ingestion API.
 * Handles category mappings for bank data import.
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
}
