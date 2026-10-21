package com.multi.vidulum.bank_data_ai_categorization.api;

import com.multi.vidulum.cashflow.api.Type;
import com.multi.vidulum.common.Money;

/**
 * One transaction, as much of it as categorization actually looks at.
 *
 * <p><b>Derived by measurement, not by convenience.</b> Ingestion's own {@code StagedTransaction}
 * carries a validation status, a staging session, mapped data, duplicate flags and paid dates; the
 * engine reads none of that. These eleven values are exactly the ones its six classes touch, which
 * is why the contract takes them rather than the row - a contract that accepted the row would make
 * every change to ingestion's storage a change to this module's input.
 *
 * @param id              the caller's own id for this transaction, echoed back in the suggestions so
 *                        it can correlate them; opaque here
 * @param name            what the bank called it
 * @param description     the bank's extra line, when there is one
 * @param money           the amount, sign included
 * @param type            INFLOW or OUTFLOW
 * @param bankCategory    the bank's own category, when the statement had one
 * @param merchant        merchant extracted upstream, when it was
 * @param merchantConfidence how sure that extraction was (0.0 - 1.0), null when there is no merchant
 * @param classification  the classification <b>word</b> (BANK_FEE, SELF_TRANSFER, MERCHANT, ...), not
 *                        an enum: it is the same text vocabulary the adapter already hands ingestion,
 *                        and a word this module has no policy for falls through to the AI
 * @param autoCategorizable whether the producer already knows this needs no model
 * @param counterpartyAccount the other party's account, when the statement carried one
 */
public record TransactionToCategorize(
        String id,
        String name,
        String description,
        Money money,
        Type type,
        String bankCategory,
        String merchant,
        Double merchantConfidence,
        String classification,
        boolean autoCategorizable,
        String counterpartyAccount
) {
    /**
     * What pattern grouping keys on: the merchant when one was extracted, the raw name otherwise.
     */
    public String effectiveMerchant() {
        return merchant != null && !merchant.isBlank() ? merchant : name;
    }

    /**
     * True when the merchant was extracted with enough confidence to group on it.
     */
    public boolean hasHighConfidenceMerchant() {
        return merchant != null && !merchant.isBlank()
                && merchantConfidence != null && merchantConfidence >= 0.7;
    }
}
