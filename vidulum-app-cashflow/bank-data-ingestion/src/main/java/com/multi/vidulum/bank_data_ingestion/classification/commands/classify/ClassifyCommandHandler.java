package com.multi.vidulum.bank_data_ingestion.classification.commands.classify;

import com.multi.vidulum.bank_data_ai_categorization.api.CategorizationSuggestions;
import com.multi.vidulum.bank_data_ai_categorization.api.CategorizationRequest;
import com.multi.vidulum.bank_data_ai_categorization.api.CategoryStructure;
import com.multi.vidulum.bank_data_ai_categorization.api.TransactionCategorizationApi;
import com.multi.vidulum.bank_data_ai_categorization.api.TransactionToCategorize;
import com.multi.vidulum.cashflow.api.CashFlowModuleApi;
import com.multi.vidulum.cashflow.api.view.CashFlowSkeletonView;
import com.multi.vidulum.bank_data_ingestion.staging.domain.*;
import com.multi.vidulum.bank_data_ingestion.staging.infrastructure.StagingSessionMongoRepository;
import com.multi.vidulum.bank_data_ingestion.staging.infrastructure.entity.StagingSessionEntity;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Handler for AI-powered transaction categorization.
 *
 * Flow:
 * 1. Ask cashflow for the skeleton - existence and category structure in one question
 * 2. Load staged transactions from session
 * 3. Build the category context from that skeleton
 * 4. Call AI Categorization Service
 * 5. Return suggestions with confidence levels
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClassifyCommandHandler
        implements CommandHandler<ClassifyCommand, CategorizationSuggestions> {

    private final CashFlowModuleApi cashFlowApi;
    private final StagedTransactionRepository stagedTransactionRepository;
    private final StagingSessionMongoRepository stagingSessionRepository;
    private final TransactionCategorizationApi categorizationApi;

    @Override
    public CategorizationSuggestions handle(ClassifyCommand command) {
        log.info("Processing AI categorization for session: {}, cashFlow: {}",
                command.sessionId(), command.cashFlowId());

        // Step 1: the cash flow's skeleton - its existence and its category structure in one
        // question. skeletonOf throws CashFlowNotFoundException on its own, so there is nothing to
        // check here, and step 3 below needs exactly what it returns.
        CashFlowSkeletonView skeleton = cashFlowApi.skeletonOf(command.cashFlowId());

        // Step 2: Load staged transactions
        List<StagedTransaction> transactions = stagedTransactionRepository
                .findByStagingSessionId(command.sessionId());

        if (transactions.isEmpty()) {
            log.warn("No staged transactions found for session: {}", command.sessionId());
            throw new StagingSessionNotFoundException(command.sessionId());
        }

        log.info("Found {} staged transactions for AI categorization", transactions.size());

        // Step 2b: Mark session as AI categorization in progress
        StagingSessionEntity sessionEntity = stagingSessionRepository
                .findBySessionId(command.sessionId().id())
                .orElse(null);
        if (sessionEntity != null) {
            sessionEntity.startClassification();
            stagingSessionRepository.save(sessionEntity);
        }

        // Step 3: Get existing categories for context (with type and hierarchy)
        CategoryStructure categoryStructure = CategoryStructure.from(skeleton);

        log.debug("Existing categories: {} inflow, {} outflow",
                categoryStructure.allInflowNames().size(),
                categoryStructure.allOutflowNames().size());

        // Step 4: Call AI Categorization Service
        // Pass cashFlowId for per-CashFlow pattern isolation
        // Pass detectedLanguage from session entity for language-aware categorization
        String detectedLanguage = sessionEntity != null ? sessionEntity.getDetectedLanguage() : null;

        CategorizationSuggestions result;
        try {
            // The contract takes what categorization actually reads from a row - not the row. Its
            // own memory of patterns is consulted inside, which is why no cache travels here and
            // why nothing is written back by this handler (see AcceptClassificationCommandHandler,
            // where a person's acceptance is what gets remembered).
            result = categorizationApi.suggestFor(new CategorizationRequest(
                    command.sessionId().id(),
                    command.cashFlowId(),
                    detectedLanguage,  // language for category name generation
                    transactions.stream().map(ClassifyCommandHandler::toCategorize).toList(),
                    categoryStructure));

            // Step 5: Mark session as AI categorization completed
            if (sessionEntity != null) {
                sessionEntity.completeClassification();
                stagingSessionRepository.save(sessionEntity);
            }
        } catch (Exception e) {
            // Mark session as AI categorization failed
            if (sessionEntity != null) {
                sessionEntity.failClassification();
                stagingSessionRepository.save(sessionEntity);
            }
            throw e;
        }

        log.info("AI categorization complete: {} patterns, {} auto-accept, {} suggested, {} manual",
                result.stats().totalPatterns(),
                result.stats().autoAccepted(),
                result.stats().suggested(),
                result.stats().needsManual());

        return result;
    }

    /**
     * Ingestion's row, narrowed to what categorization reads. Eleven values out of a staged
     * transaction's two dozen - and the eleven were measured from the engine's own code, not chosen.
     */
    private static TransactionToCategorize toCategorize(StagedTransaction transaction) {
        var original = transaction.originalData();
        return new TransactionToCategorize(
                transaction.stagedTransactionId().id(),
                original.name(),
                original.description(),
                original.money(),
                original.type(),
                original.bankCategory(),
                original.merchant(),
                original.merchantConfidence(),
                // the vocabulary word, not the enum: the same text contract the adapter uses
                original.effectiveClassification().name(),
                original.isAutoCategorizeable(),
                original.counterpartyAccount());
    }
}
