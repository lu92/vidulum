package com.multi.vidulum.bank_data_ai_categorization.api;

import com.multi.vidulum.cashflow.api.Type;

/**
 * A pattern the owner has just confirmed, offered to the module's memory.
 *
 * <p>Sent when a person accepts a suggestion - that acceptance is the only evidence worth
 * remembering, which is why nothing is learned at suggestion time. A pattern already known for the
 * same cash flow, pattern and type is updated rather than duplicated; that is the implementation's
 * promise, not the caller's problem.
 *
 * @param normalizedPattern  the pattern as the engine normalises names, <b>not</b> the raw bank text
 * @param type               the side it applies to
 * @param category           what to categorize such transactions as
 * @param parentCategory     the hierarchy intent, when there is one
 * @param confidence         0.0 - 1.0 as given by whoever taught it
 * @param source             who taught it: a person, the model, or the seed list
 */
public record PatternToLearn(
        String normalizedPattern,
        Type type,
        String category,
        String parentCategory,
        double confidence,
        PatternSource source
) {
}
