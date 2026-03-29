package com.multi.vidulum.bank_data_ingestion.app.commands.ai_categorize;

import com.multi.vidulum.bank_data_ingestion.app.CashFlowInfo;
import com.multi.vidulum.bank_data_ingestion.app.CashFlowServiceClient;
import com.multi.vidulum.bank_data_ingestion.app.categorization.AiCategorizationService;
import com.multi.vidulum.bank_data_ingestion.domain.*;
import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Handler for AI-powered transaction categorization.
 *
 * Flow:
 * 1. Verify CashFlow exists
 * 2. Load staged transactions from session
 * 3. Get existing categories for context
 * 4. Call AI Categorization Service
 * 5. Return suggestions with confidence levels
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiCategorizeCommandHandler
        implements CommandHandler<AiCategorizeCommand, AiCategorizationResult> {

    private final CashFlowServiceClient cashFlowServiceClient;
    private final StagedTransactionRepository stagedTransactionRepository;
    private final AiCategorizationService aiCategorizationService;

    @Override
    public AiCategorizationResult handle(AiCategorizeCommand command) {
        log.info("Processing AI categorization for session: {}, cashFlow: {}",
                command.sessionId(), command.cashFlowId());

        // Step 1: Verify CashFlow exists
        if (!cashFlowServiceClient.exists(command.cashFlowId().id())) {
            throw new CashFlowDoesNotExistsException(command.cashFlowId());
        }

        // Step 2: Load staged transactions
        List<StagedTransaction> transactions = stagedTransactionRepository
                .findByStagingSessionId(command.sessionId());

        if (transactions.isEmpty()) {
            log.warn("No staged transactions found for session: {}", command.sessionId());
            throw new StagingSessionNotFoundException(command.sessionId());
        }

        log.info("Found {} staged transactions for AI categorization", transactions.size());

        // Step 3: Get existing categories for context
        CashFlowInfo cashFlowInfo = cashFlowServiceClient.getCashFlowInfo(command.cashFlowId().id());
        List<String> existingCategories = collectCategoryNames(cashFlowInfo);

        log.debug("Existing categories: {}", existingCategories.size());

        // Step 4: Call AI Categorization Service
        AiCategorizationResult result = aiCategorizationService.categorize(
                command.sessionId(),
                transactions,
                command.userId(),
                existingCategories
        );

        log.info("AI categorization complete: {} patterns, {} auto-accept, {} suggested, {} manual",
                result.stats().totalPatterns(),
                result.stats().autoAccepted(),
                result.stats().suggested(),
                result.stats().needsManual());

        return result;
    }

    /**
     * Collects all category names from CashFlowInfo (flattened).
     */
    private List<String> collectCategoryNames(CashFlowInfo info) {
        List<String> names = new ArrayList<>();
        collectNames(info.inflowCategories(), names);
        collectNames(info.outflowCategories(), names);
        return names;
    }

    private void collectNames(List<CashFlowInfo.CategoryInfo> categories, List<String> names) {
        for (CashFlowInfo.CategoryInfo cat : categories) {
            names.add(cat.name());
            collectNames(cat.subCategories(), names);
        }
    }
}
