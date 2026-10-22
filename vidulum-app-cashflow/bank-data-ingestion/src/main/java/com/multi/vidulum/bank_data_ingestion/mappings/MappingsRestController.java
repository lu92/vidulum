package com.multi.vidulum.bank_data_ingestion.mappings;

import com.multi.vidulum.bank_data_ingestion.app.BankDataIngestionDto;
import com.multi.vidulum.bank_data_ingestion.mappings.commands.configure_mapping.ConfigureCategoryMappingCommand;
import com.multi.vidulum.bank_data_ingestion.mappings.commands.configure_mapping.ConfigureCategoryMappingResult;
import com.multi.vidulum.bank_data_ingestion.mappings.commands.delete_all_mappings.DeleteAllCategoryMappingsCommand;
import com.multi.vidulum.bank_data_ingestion.mappings.commands.delete_all_mappings.DeleteAllCategoryMappingsResult;
import com.multi.vidulum.bank_data_ingestion.mappings.commands.delete_mapping.DeleteCategoryMappingCommand;
import com.multi.vidulum.bank_data_ingestion.mappings.commands.delete_mapping.DeleteCategoryMappingResult;
import com.multi.vidulum.bank_data_ingestion.mappings.queries.get_mappings.GetCategoryMappingsQuery;
import com.multi.vidulum.bank_data_ingestion.mappings.queries.get_mappings.GetCategoryMappingsResult;
import com.multi.vidulum.bank_data_ingestion.mappings.domain.*;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.shared.cqrs.CommandGateway;
import com.multi.vidulum.shared.cqrs.QueryGateway;
import java.util.List;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Category mappings over HTTP: four endpoints, and the section behind them owns one collection.

 * <p>Split out of the 1,195-line {@code BankDataIngestionRestController}, which named every
 * section's commands and was therefore the one place where the sections were welded together - it
 * is the reason the cycle rule in {@code CanonicalRowIsSelfContainedTest} needed an exemption at
 * all. <b>The URL prefix is unchanged</b>, so this is invisible from outside.
 */
@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/bank-data-ingestion/cf={cashFlowId}")
public class MappingsRestController {

    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;

    /**
     * Configure category mappings for bank data ingestion.
     * This replaces existing mappings for the same (bankCategoryName, categoryType) combination.
     */
    @PostMapping("/mappings")
    public MappingsDto.ConfigureMappingsResponse configureMappings(
            @PathVariable("cashFlowId") String cashFlowId,
            @RequestBody MappingsDto.ConfigureMappingsRequest request) {

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
    public MappingsDto.GetMappingsResponse getMappings(
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
    public MappingsDto.DeleteMappingResponse deleteMapping(
            @PathVariable("cashFlowId") String cashFlowId,
            @PathVariable("mappingId") String mappingId) {

        DeleteCategoryMappingResult result = commandGateway.send(
                new DeleteCategoryMappingCommand(
                        CashFlowId.of(cashFlowId),
                        MappingId.of(mappingId)
                )
        );

        return MappingsDto.DeleteMappingResponse.builder()
                .deleted(result.deleted())
                .mappingId(result.mappingId().id())
                .bankCategoryName(result.bankCategoryName())
                .build();
    }
    /**
     * Delete all category mappings for a CashFlow.
     */
    @DeleteMapping("/mappings")
    public MappingsDto.DeleteAllMappingsResponse deleteAllMappings(
            @PathVariable("cashFlowId") String cashFlowId) {

        DeleteAllCategoryMappingsResult result = commandGateway.send(
                new DeleteAllCategoryMappingsCommand(CashFlowId.of(cashFlowId))
        );

        return MappingsDto.DeleteAllMappingsResponse.builder()
                .deleted(result.deleted())
                .deletedCount(result.deletedCount())
                .build();
    }
    private ConfigureCategoryMappingCommand.MappingConfig toMappingConfig(
            MappingsDto.MappingConfigJson json) {

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
                json.getAction(),
                null  // manual mappings don't have AI confidence
        );
    }
    private MappingsDto.ConfigureMappingsResponse toConfigureMappingsResponse(
            ConfigureCategoryMappingResult result) {

        List<MappingsDto.MappingResultJson> mappingResults = result.configuredMappings().stream()
                .map(r -> MappingsDto.MappingResultJson.builder()
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

        return MappingsDto.ConfigureMappingsResponse.builder()
                .cashFlowId(result.cashFlowId().id())
                .mappingsConfigured(result.mappingsConfigured())
                .mappings(mappingResults)
                .build();
    }
    private MappingsDto.GetMappingsResponse toGetMappingsResponse(
            GetCategoryMappingsResult result) {

        List<MappingsDto.MappingJson> mappings = result.mappings().stream()
                .map(this::toMappingJson)
                .toList();

        return MappingsDto.GetMappingsResponse.builder()
                .cashFlowId(result.cashFlowId().id())
                .mappingsCount(result.mappingsCount())
                .mappings(mappings)
                .build();
    }
    private MappingsDto.MappingJson toMappingJson(CategoryMapping mapping) {
        return MappingsDto.MappingJson.builder()
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
