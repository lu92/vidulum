package com.multi.vidulum.bank_data_ingestion.app.commands.configure_mapping;

import com.multi.vidulum.bank_data_ingestion.domain.CategoryMapping;
import com.multi.vidulum.bank_data_ingestion.domain.CategoryMappingRepository;
import com.multi.vidulum.cashflow.domain.CashFlow;
import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.DomainCashFlowRepository;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@AllArgsConstructor
public class ConfigureCategoryMappingCommandHandler
        implements CommandHandler<ConfigureCategoryMappingCommand, ConfigureCategoryMappingResult> {

    private final CategoryMappingRepository categoryMappingRepository;
    private final DomainCashFlowRepository domainCashFlowRepository;
    private final Clock clock;

    @Override
    public ConfigureCategoryMappingResult handle(ConfigureCategoryMappingCommand command) {
        // Verify CashFlow exists
        CashFlow cashFlow = domainCashFlowRepository.findById(command.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(command.cashFlowId()));

        ZonedDateTime now = ZonedDateTime.now(clock);
        List<ConfigureCategoryMappingResult.MappingResult> results = new ArrayList<>();

        for (ConfigureCategoryMappingCommand.MappingConfig config : command.mappings()) {
            // Check if mapping already exists for this combination
            Optional<CategoryMapping> existingMapping = categoryMappingRepository
                    .findByCashFlowIdAndBankCategoryNameAndCategoryType(
                            command.cashFlowId(),
                            config.bankCategoryName(),
                            config.categoryType()
                    );

            CategoryMapping savedMapping;
            ConfigureCategoryMappingResult.MappingStatus status;

            if (existingMapping.isPresent()) {
                // Update existing mapping
                CategoryMapping updated = existingMapping.get().update(
                        config.targetCategoryName(),
                        config.parentCategoryName(),
                        config.action(),
                        now
                );
                savedMapping = categoryMappingRepository.save(updated);
                status = ConfigureCategoryMappingResult.MappingStatus.UPDATED;
                log.info("Updated category mapping for bank category [{}] type [{}] in CashFlow [{}]",
                        config.bankCategoryName(), config.categoryType(), command.cashFlowId().id());
            } else {
                // Create new mapping
                CategoryMapping newMapping = CategoryMapping.create(
                        command.cashFlowId(),
                        config.bankCategoryName(),
                        config.targetCategoryName(),
                        config.parentCategoryName(),
                        config.categoryType(),
                        config.action(),
                        now
                );
                savedMapping = categoryMappingRepository.save(newMapping);
                status = ConfigureCategoryMappingResult.MappingStatus.CREATED;
                log.info("Created category mapping for bank category [{}] type [{}] in CashFlow [{}]",
                        config.bankCategoryName(), config.categoryType(), command.cashFlowId().id());
            }

            results.add(new ConfigureCategoryMappingResult.MappingResult(savedMapping, status));
        }

        log.info("Configured {} category mappings for CashFlow [{}]",
                results.size(), command.cashFlowId().id());

        return new ConfigureCategoryMappingResult(
                command.cashFlowId(),
                results.size(),
                results
        );
    }
}
