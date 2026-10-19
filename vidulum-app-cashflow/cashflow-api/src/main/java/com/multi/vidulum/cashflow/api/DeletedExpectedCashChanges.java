package com.multi.vidulum.cashflow.api;

/**
 * What came back from withdrawing a batch of expected cash changes.
 *
 * <p>{@code skipped} is not a failure. A cash change the consumer asked to remove may no longer be
 * pending - the owner confirmed it, so it became a fact about their money rather than something a
 * rule predicted - and cashflow leaves those alone. A caller that cares can say so; one that does
 * not can ignore both numbers, which is why nothing here throws.
 */
public record DeletedExpectedCashChanges(int deleted, int skipped) {
}
