package com.multi.vidulum.cashflow.api;

/**
 * How many of the cash changes something produced are still predictions, and how many have become
 * facts.
 *
 * <p>Answers one question for one caller: a recurring rule asking what deleting it would cost. The
 * {@code pending} ones are what {@link CashFlowModuleApi#deleteExpected} would withdraw; the
 * {@code confirmed} ones are the owner's now and would stay.
 *
 * <p><b>{@code other} exists so that a status nobody asked about cannot vanish.</b> A cash flow
 * knows four states, not two - rejected and archived are the other two - and the consumer this
 * replaced compared {@code "PENDING"} and {@code "CONFIRMED"} as string literals, so anything else
 * was silently counted as neither and the preview's own total did not add up. Nothing has to read
 * {@code other}; it only has to be impossible to lose.
 */
public record ExpectedCashChangesByStatus(int pending, int confirmed, int other) {

    public int total() {
        return pending + confirmed + other;
    }
}
