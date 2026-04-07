package com.multi.vidulum.bank_data_ingestion.app.commands.accept_ai_suggestions;

import com.multi.vidulum.bank_data_ingestion.app.CashFlowServiceClient;
import com.multi.vidulum.bank_data_ingestion.app.commands.configure_mapping.ConfigureCategoryMappingCommand;
import com.multi.vidulum.bank_data_ingestion.app.commands.configure_mapping.ConfigureCategoryMappingCommandHandler;
import com.multi.vidulum.bank_data_ingestion.app.commands.configure_mapping.ConfigureCategoryMappingResult;
import com.multi.vidulum.bank_data_ingestion.app.commands.revalidate_staging.RevalidateStagingCommand;
import com.multi.vidulum.bank_data_ingestion.app.commands.revalidate_staging.RevalidateStagingCommandHandler;
import com.multi.vidulum.bank_data_ingestion.app.commands.revalidate_staging.RevalidateStagingResult;
import com.multi.vidulum.bank_data_ingestion.domain.*;
import com.multi.vidulum.cashflow.domain.CategoryName;
import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Handler for accepting AI categorization suggestions.
 *
 * Flow:
 * 1. Create new categories in CashFlow
 * 2. Create category mappings (bank → CashFlow)
 * 3. Save pattern mappings to user cache (optional)
 * 4. Revalidate staging session
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AcceptAiSuggestionsCommandHandler
        implements CommandHandler<AcceptAiSuggestionsCommand, AcceptAiSuggestionsResult> {

    private final CashFlowServiceClient cashFlowServiceClient;
    private final PatternMappingRepository patternMappingRepository;
    private final StagedTransactionRepository stagedTransactionRepository;
    private final ConfigureCategoryMappingCommandHandler mappingHandler;
    private final RevalidateStagingCommandHandler revalidateHandler;
    private final Clock clock;

    @Override
    public AcceptAiSuggestionsResult handle(AcceptAiSuggestionsCommand command) {
        log.info("Accepting AI suggestions for session: {}, cashFlow: {}",
                command.sessionId(), command.cashFlowId());

        // Verify CashFlow exists
        if (!cashFlowServiceClient.exists(command.cashFlowId().id())) {
            throw new CashFlowDoesNotExistsException(command.cashFlowId());
        }

        // Verify session exists
        List<StagedTransaction> transactions = stagedTransactionRepository
                .findByStagingSessionId(command.sessionId());
        if (transactions.isEmpty()) {
            throw new StagingSessionNotFoundException(command.sessionId());
        }

        List<String> warnings = new ArrayList<>();
        int categoriesCreated = 0;
        int mappingsApplied = 0;
        int patternsCached = 0;

        // Step 1: Create categories
        if (command.acceptedCategories() != null && !command.acceptedCategories().isEmpty()) {
            for (AcceptAiSuggestionsCommand.CategoryToCreate cat : command.acceptedCategories()) {
                try {
                    cashFlowServiceClient.createCategory(
                            command.cashFlowId().id(),
                            cat.name(),
                            cat.parentName(),
                            cat.type()
                    );
                    categoriesCreated++;
                    log.debug("Created category: {} (parent: {})", cat.name(), cat.parentName());
                } catch (CashFlowServiceClient.CategoryAlreadyExistsException e) {
                    log.debug("Category already exists: {}", cat.name());
                    // Not an error - category may have been created by user or previous import
                } catch (Exception e) {
                    warnings.add("Failed to create category: " + cat.name() + " - " + e.getMessage());
                    log.warn("Failed to create category: {}", cat.name(), e);
                }
            }
        }

        // Step 2: Create category mappings
        // Note: parentCategory is no longer used - mappings are to leaf category name only.
        // The parent relationship is looked up dynamically from CashFlow.
        if (command.acceptedMappings() != null && !command.acceptedMappings().isEmpty()) {
            List<ConfigureCategoryMappingCommand.MappingConfig> mappingConfigs = new ArrayList<>();

            for (AcceptAiSuggestionsCommand.MappingToApply mapping : command.acceptedMappings()) {
                // Use MAP_TO_EXISTING since category should already exist
                MappingAction action = MappingAction.MAP_TO_EXISTING;

                // Create mapping config for bank category → target category
                mappingConfigs.add(new ConfigureCategoryMappingCommand.MappingConfig(
                        mapping.bankCategory(),
                        new CategoryName(mapping.targetCategory()),
                        null,  // parentCategory not stored in mapping - looked up dynamically
                        mapping.type(),
                        action,
                        mapping.confidence()
                ));
            }

            if (!mappingConfigs.isEmpty()) {
                ConfigureCategoryMappingCommand mappingCommand = new ConfigureCategoryMappingCommand(
                        command.cashFlowId(),
                        mappingConfigs
                );

                ConfigureCategoryMappingResult mappingResult = mappingHandler.handle(mappingCommand);
                mappingsApplied = mappingResult.mappingsConfigured();
                log.info("Applied {} category mappings", mappingsApplied);
            }
        }

        // Step 2b: Create bank category mappings
        int bankCategoryMappingsApplied = 0;
        if (command.acceptedBankCategoryMappings() != null && !command.acceptedBankCategoryMappings().isEmpty()) {
            List<ConfigureCategoryMappingCommand.MappingConfig> bankMappingConfigs = new ArrayList<>();

            for (AcceptAiSuggestionsCommand.BankCategoryMappingToApply mapping : command.acceptedBankCategoryMappings()) {
                bankMappingConfigs.add(new ConfigureCategoryMappingCommand.MappingConfig(
                        mapping.bankCategory(),
                        new CategoryName(mapping.targetCategory()),
                        null,  // parentCategory looked up dynamically
                        mapping.type(),
                        MappingAction.MAP_TO_EXISTING,
                        mapping.confidence()
                ));
            }

            if (!bankMappingConfigs.isEmpty()) {
                ConfigureCategoryMappingCommand bankMappingCommand = new ConfigureCategoryMappingCommand(
                        command.cashFlowId(),
                        bankMappingConfigs
                );

                ConfigureCategoryMappingResult bankMappingResult = mappingHandler.handle(bankMappingCommand);
                bankCategoryMappingsApplied = bankMappingResult.mappingsConfigured();
                log.info("Applied {} bank category mappings", bankCategoryMappingsApplied);
            }
        }

        // Step 3: Save to pattern cache (if requested)
        // Patterns are stored per CashFlow (not per user) for better isolation
        if (command.saveToCache() && command.acceptedMappings() != null) {
            String cashFlowIdStr = command.cashFlowId().id();

            for (AcceptAiSuggestionsCommand.MappingToApply mapping : command.acceptedMappings()) {
                try {
                    // Check if this CashFlow already has this pattern
                    boolean exists = patternMappingRepository
                            .findUserByNormalizedPatternAndTypeAndCashFlowId(
                                    mapping.pattern().toUpperCase(),
                                    mapping.type(),
                                    cashFlowIdStr
                            ).isPresent();

                    if (!exists) {
                        PatternMapping patternMapping = PatternMapping.createUser(
                                mapping.pattern().toUpperCase(),
                                mapping.targetCategory(),
                                mapping.type(),
                                command.userId(),
                                cashFlowIdStr,
                                (double) mapping.confidence() / 100.0
                        );
                        patternMappingRepository.save(patternMapping);
                        patternsCached++;
                    }
                } catch (Exception e) {
                    log.warn("Failed to cache pattern: {}", mapping.pattern(), e);
                }
            }
            log.info("Cached {} patterns for cashFlow: {}", patternsCached, cashFlowIdStr);
        }

        // Step 4: Revalidate staging session
        RevalidateStagingCommand revalidateCommand = new RevalidateStagingCommand(
                command.cashFlowId(),
                command.sessionId()
        );

        RevalidateStagingResult revalidateResult = revalidateHandler.handle(revalidateCommand);

        // Build validation summary
        AcceptAiSuggestionsResult.StagingValidationSummary validationSummary =
                new AcceptAiSuggestionsResult.StagingValidationSummary(
                        revalidateResult.summary().totalTransactions(),
                        revalidateResult.summary().validCount(),
                        revalidateResult.summary().invalidCount(),
                        revalidateResult.summary().duplicateCount(),
                        revalidateResult.status() == RevalidateStagingResult.Status.SUCCESS
                );

        log.info("Accept AI suggestions complete: {} categories, {} pattern mappings, {} bank category mappings, {} cached. Session ready: {}",
                categoriesCreated, mappingsApplied, bankCategoryMappingsApplied, patternsCached, validationSummary.readyForImport());

        if (warnings.isEmpty()) {
            return AcceptAiSuggestionsResult.success(
                    command.cashFlowId(),
                    command.sessionId(),
                    categoriesCreated,
                    mappingsApplied,
                    patternsCached,
                    validationSummary
            );
        } else {
            return AcceptAiSuggestionsResult.partial(
                    command.cashFlowId(),
                    command.sessionId(),
                    categoriesCreated,
                    mappingsApplied,
                    patternsCached,
                    warnings,
                    validationSummary
            );
        }
    }
}
