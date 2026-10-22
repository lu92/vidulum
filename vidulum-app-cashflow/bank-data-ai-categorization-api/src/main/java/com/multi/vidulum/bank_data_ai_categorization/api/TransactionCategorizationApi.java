package com.multi.vidulum.bank_data_ai_categorization.api;

import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.shared.module.ModuleApi;

import java.util.List;

/**
 * What another module may ask of AI categorization.
 *
 * <p>Two questions and one statement, each from a real call site rather than from the engine's
 * surface:
 *
 * <ul>
 *   <li>{@link #suggestFor} - {@code ClassifyCommandHandler}, which had the engine injected
 *       directly along with its pattern repository.</li>
 *   <li>{@link #matchLearned} - the two staging handlers that applied learned patterns with their
 *       own copy of the matching rule. This is the method that makes the rule exist once.</li>
 *   <li>{@link #learn} - {@code AcceptClassificationCommandHandler}, which wrote pattern documents
 *       itself.</li>
 * </ul>
 *
 * <p><b>What is deliberately absent.</b> No method hands out the patterns of a cash flow. The
 * caller that used to fetch them did so only to match names against them, and that is
 * {@link #matchLearned}; a {@code patternsFor(cashFlowId)} would invite the next caller to re-create
 * the rule this contract exists to keep in one place - the same reasoning that made
 * {@code ImportWindow} a value rather than a predicate asked per row.
 *
 * <p>Nothing here says "AI". A cache hit answers without a model, a pre-filtered bank fee answers
 * without a model, and a consumer should not have to care which - the module is named for what it
 * does, and the contract for what it is asked.
 */
public interface TransactionCategorizationApi extends ModuleApi {

    /**
     * Suggests categories for a batch: the module's own memory first, the model for what is left.
     *
     * <p>Costs money when it reaches the model, so the result carries what it cost. Nothing is
     * remembered here - see {@link #learn}.
     */
    CategorizationSuggestions suggestFor(CategorizationRequest request);

    /**
     * Offers a batch to what this cash flow has already taught the module, without a model.
     *
     * <p>Free, and the answer is the same rule the patterns were learned under.
     */
    PatternMatches matchLearned(CashFlowId cashFlowId, List<TransactionToMatch> transactions);

    /**
     * Remembers patterns a person has confirmed, for this cash flow only.
     *
     * @return how many were stored as new, as opposed to updating one already known
     */
    int learn(CashFlowId cashFlowId, List<PatternToLearn> patterns);

    /**
     * Forgets everything this cash flow taught the module.
     *
     * <p>For the rollback of an import: if the transactions it categorized are being withdrawn, the
     * patterns it learned from that person's acceptances are the same decision and go with them.
     */
    long forget(CashFlowId cashFlowId);
}
