package com.multi.vidulum.cashflow.api.view;

import com.multi.vidulum.common.CashChangeId;
import com.multi.vidulum.common.CategoryName;
import com.multi.vidulum.common.Money;

import java.time.ZonedDateTime;

/**
 * One transaction a cash flow took in from somewhere else, and what it became here.
 *
 * <p><b>A correspondence, not a copy.</b> The point is the pair: the id the source system used and
 * the {@link CashChangeId} cashflow issued for it. Everything else is here so that a human can
 * check the claim.
 *
 * <p><b>Why {@code cashChangeId} matters.</b> Staging reports duplicates to the user through
 * {@code DuplicateInfoJson}, whose {@code duplicateOf} field is documented as "ID of the existing
 * transaction this is a duplicate of" - and which used to hold the id of the row being examined,
 * because the method answering this question returned a bare {@code Set<String>} and the existing
 * cash change's id never crossed the boundary. The field asked a question and answered it with
 * itself.
 *
 * <p><b>Why {@code paidDate}, {@code money} and {@code categoryName} matter.</b> "12 of your 402
 * rows are duplicates" is not something a person can verify. Two genuine visits to the same shop on
 * the same day for the same amount are a real case - {@code shouldNotConfuseTwoGenuinelyDifferentTransactions}
 * exists precisely because only the source id tells them apart - so a user looking at a duplicate
 * list needs to see what cashflow already holds, not just that it holds something.
 *
 * <p>Note on the date: cashflow stores a cash change's paid date in its {@code endDate} field -
 * {@code CashChangeSnapshot} has no {@code paidDate} of its own - which is worth knowing before
 * reading either implementation of this contract.
 *
 * @param sourceId      the id the source system used; never {@code null} here, because a cash
 *                      change without one is simply not in {@link ImportedTransactions}
 * @param cashChangeId  what cashflow recorded it as
 * @param paidDate      when it was paid, as cashflow holds it
 * @param money         the amount, with the scale the source reported
 * @param categoryName  where it landed
 */
public record ImportedTransaction(
        String sourceId,
        CashChangeId cashChangeId,
        ZonedDateTime paidDate,
        Money money,
        CategoryName categoryName) {
}
