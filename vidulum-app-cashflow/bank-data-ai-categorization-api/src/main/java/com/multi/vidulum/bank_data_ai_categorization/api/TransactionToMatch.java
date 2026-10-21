package com.multi.vidulum.bank_data_ai_categorization.api;

import com.multi.vidulum.cashflow.api.Type;

/**
 * A transaction offered to the learned patterns, without asking the model anything.
 *
 * @param id   the caller's id, so the answer can be keyed back to the row
 * @param name what the bank called it
 * @param type INFLOW or OUTFLOW - a pattern learned on one side must not match the other
 */
public record TransactionToMatch(
        String id,
        String name,
        Type type
) {
}
