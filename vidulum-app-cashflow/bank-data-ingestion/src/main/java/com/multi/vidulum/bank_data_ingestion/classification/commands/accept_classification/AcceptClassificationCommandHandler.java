package com.multi.vidulum.bank_data_ingestion.classification.commands.accept_classification;
import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.bank_data_ingestion.mappings.commands.configure_mapping.ConfigureCategoryMappingCommand;
import com.multi.vidulum.bank_data_ingestion.mappings.commands.configure_mapping.ConfigureCategoryMappingCommandHandler;
import com.multi.vidulum.bank_data_ingestion.mappings.commands.configure_mapping.ConfigureCategoryMappingResult;
import com.multi.vidulum.bank_data_ingestion.staging.commands.revalidate_staging.RevalidateStagingCommand;
import com.multi.vidulum.bank_data_ingestion.staging.commands.revalidate_staging.RevalidateStagingCommandHandler;
import com.multi.vidulum.bank_data_ingestion.staging.commands.revalidate_staging.RevalidateStagingResult;
import com.multi.vidulum.bank_data_ai_categorization.api.PatternSource;
import com.multi.vidulum.bank_data_ai_categorization.api.PatternToLearn;
import com.multi.vidulum.bank_data_ai_categorization.api.TransactionCategorizationApi;
import com.multi.vidulum.bank_data_ingestion.mappings.domain.*;
import com.multi.vidulum.bank_data_ingestion.staging.domain.*;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.cashflow.api.CashFlowModuleApi;
import com.multi.vidulum.cashflow.api.error.CashFlowNotFoundException;
import com.multi.vidulum.cashflow.api.error.CategoryAlreadyExistsException;
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
public class AcceptClassificationCommandHandler
        implements CommandHandler<AcceptClassificationCommand, AcceptClassificationResult> {

    private final CashFlowModuleApi cashFlowApi;
    private final TransactionCategorizationApi categorizationApi;
    private final StagedTransactionRepository stagedTransactionRepository;
    private final ConfigureCategoryMappingCommandHandler mappingHandler;
    private final RevalidateStagingCommandHandler revalidateHandler;
    private final Clock clock;

    @Override
    public AcceptClassificationResult handle(AcceptClassificationCommand command) {
        log.info("Accepting AI suggestions for session: {}, cashFlow: {}",
                command.sessionId(), command.cashFlowId());

        // Verify CashFlow exists
        if (!cashFlowApi.exists(command.cashFlowId())) {
            throw new CashFlowNotFoundException(command.cashFlowId());
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

        // Step 1: Create categories
        if (command.acceptedCategories() != null && !command.acceptedCategories().isEmpty()) {
            for (AcceptClassificationCommand.CategoryToCreate cat : command.acceptedCategories()) {
                try {
                    cashFlowApi.createCategory(
                            command.cashFlowId(),
                            new CategoryName(cat.name()),
                            cat.parentName() == null ? null : new CategoryName(cat.parentName()),
                            cat.type(),
                            true);
                    categoriesCreated++;
                    log.debug("Created category: {} (parent: {})", cat.name(), cat.parentName());
                } catch (CategoryAlreadyExistsException e) {
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

            for (AcceptClassificationCommand.MappingToApply mapping : command.acceptedMappings()) {
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

            for (AcceptClassificationCommand.BankCategoryMappingToApply mapping : command.acceptedBankCategoryMappings()) {
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

        // Step 3: Let AI categorization remember what the person accepted
        //
        // It used to write pattern documents here - checking for an existing one, minting a
        // PatternMapping, saving it - which made this handler a second writer of somebody else's
        // memory. The contract takes what was confirmed and decides itself whether that is a new
        // pattern or a correction of one it already knew.
        int patternsCached = 0;
        if (command.saveToCache() && command.acceptedMappings() != null) {
            List<PatternToLearn> confirmed = command.acceptedMappings().stream()
                    .map(mapping -> new PatternToLearn(
                            mapping.pattern(),
                            mapping.type(),
                            mapping.targetCategory(),
                            mapping.parentCategory(),   // the AI's intended parent, kept as a hint
                            (double) mapping.confidence() / 100.0,
                            PatternSource.USER))        // a person accepted it, whoever proposed it
                    .toList();

            patternsCached = categorizationApi.learn(command.cashFlowId(), confirmed);
            log.info("Remembered {} patterns for cashFlow: {}", patternsCached, command.cashFlowId().id());
        }

        // Step 4: Revalidate staging session
        RevalidateStagingCommand revalidateCommand = new RevalidateStagingCommand(
                command.cashFlowId(),
                command.sessionId()
        );

        RevalidateStagingResult revalidateResult = revalidateHandler.handle(revalidateCommand);

        // Build validation summary
        AcceptClassificationResult.StagingValidationSummary validationSummary =
                new AcceptClassificationResult.StagingValidationSummary(
                        revalidateResult.summary().totalTransactions(),
                        revalidateResult.summary().validCount(),
                        revalidateResult.summary().invalidCount(),
                        revalidateResult.summary().duplicateCount(),
                        revalidateResult.status() == RevalidateStagingResult.Status.SUCCESS
                );

        log.info("Accept AI suggestions complete: {} categories, {} pattern mappings, {} bank category mappings, {} cached. Session ready: {}",
                categoriesCreated, mappingsApplied, bankCategoryMappingsApplied, patternsCached, validationSummary.readyForImport());

        if (warnings.isEmpty()) {
            return AcceptClassificationResult.success(
                    command.cashFlowId(),
                    command.sessionId(),
                    categoriesCreated,
                    mappingsApplied,
                    patternsCached,
                    validationSummary
            );
        } else {
            return AcceptClassificationResult.partial(
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
