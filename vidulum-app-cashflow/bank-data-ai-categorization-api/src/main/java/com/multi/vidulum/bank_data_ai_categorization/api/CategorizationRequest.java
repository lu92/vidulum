package com.multi.vidulum.bank_data_ai_categorization.api;

import com.multi.vidulum.common.CashFlowId;

import java.util.List;

/**
 * Everything needed to categorize one batch of transactions.
 *
 * <p>No learned patterns travel in here, and that is the point of the module owning its own memory:
 * the caller says which cash flow is being imported and the engine looks up what it already knows.
 * Passing the cache in would make every caller responsible for reading it, writing back what was
 * learned, and recording which entries were used - three things that are not the caller's business
 * and were, before this split, spread across three handlers.
 *
 * @param sessionId         opaque correlation id, echoed back in the result
 * @param cashFlowId        whose memory to consult; patterns are per cash flow, never shared
 * @param detectedLanguage  language the statement was written in ("pl", "en", ...), may be null -
 *                          it decides what language new category names are invented in
 * @param transactions      the batch
 * @param categoryStructure the categories that already exist, so the model proposes names that fit
 */
public record CategorizationRequest(
        String sessionId,
        CashFlowId cashFlowId,
        String detectedLanguage,
        List<TransactionToCategorize> transactions,
        CategoryStructure categoryStructure
) {
}
