package com.multi.vidulum.bank_data_ai_categorization.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multi.vidulum.bank_data_ai_categorization.api.TransactionCategorizationApi;
import com.multi.vidulum.bank_data_ai_categorization.api.TransactionCategorizationApiContractTest;
import com.multi.vidulum.bank_data_ai_categorization.domain.InMemoryPatternMappingRepository;
import com.multi.vidulum.bank_data_ai_categorization.domain.PatternMappingRepository;
import com.multi.vidulum.common.CashFlowId;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * This module running the contract's own test suite on itself.
 *
 * <p>The suite lives in the api module and ships as a test-jar, so a second implementation - a
 * static classifier, another model - extends the same class instead of being trusted. That was the
 * gap the F66 review named: the swap was structural, and a replacement had nothing to run.
 *
 * <p><b>The model is a stub that refuses to be called.</b> Nothing the contract promises needs one:
 * matching, learning and forgetting are the module's own memory, and the two {@code suggestFor}
 * cases are a batch with nothing in it and a batch of rows the producer already marked
 * auto-categorizable. If a future change makes either of those pay for a model call, this stub turns
 * that into a failed test rather than a bill.
 */
class AiEngineMeetsTheContractTest extends TransactionCategorizationApiContractTest {

    private static final AtomicInteger NEXT_CASH_FLOW = new AtomicInteger(1);

    private final PatternMappingRepository patterns = new InMemoryPatternMappingRepository();
    private final TransactionNameNormalizer normalizer = new TransactionNameNormalizer();

    private final TransactionCategorizationApi api = new TransactionCategorizationApiImpl(
            new AiCategorizationService(
                    refusingModel(),
                    normalizer,
                    new PatternDeduplicator(normalizer),
                    patterns,
                    new AiCategorizationPromptBuilder(),
                    new AiCategorizationResponseParser(new ObjectMapper())),
            new LearnedPatternMatcher(patterns, normalizer),
            patterns);

    @Override
    protected TransactionCategorizationApi categorizationApi() {
        return api;
    }

    @Override
    protected CashFlowId freshCashFlow() {
        return CashFlowId.of("CF%08d".formatted(NEXT_CASH_FLOW.getAndIncrement()));
    }

    private static ChatModel refusingModel() {
        return (Prompt prompt) -> {
            throw new AssertionError("the contract's promises must not need a model: " + prompt);
        };
    }
}
