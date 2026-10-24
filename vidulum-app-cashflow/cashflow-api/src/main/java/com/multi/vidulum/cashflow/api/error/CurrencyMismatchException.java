package com.multi.vidulum.cashflow.api.error;

import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.common.Currency;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * Money in a currency the cash flow does not hold.
 *
 * <p><b>Why this exists at all.</b> {@code Money.plus} keeps the currency of the left operand and
 * adds the amount of the right one, with no check - so 22.14 EUR added to a PLN balance becomes
 * 22.14 PLN, silently. Measured on a real statement: two EUR rows among 789 PLN ones would move a
 * cash flow's balance by 34.32 PLN instead of the ~147 PLN they are worth, and the owner would meet
 * the difference once, at attestation, as a balance mismatch with nothing explaining it.
 *
 * <p>Thrown by nobody yet, and that is deliberate: it is handed back by
 * {@link com.multi.vidulum.cashflow.api.view.CashFlowSkeletonView#refusalFor} so a consumer
 * importing a statement can refuse the row <b>before</b> the write, and record this error code
 * against it. The same reasoning as the five import-date refusals next to it.
 */
public class CurrencyMismatchException extends BusinessException {

    private final CashFlowId cashFlowId;
    private final Currency expectedCurrency;
    private final Currency actualCurrency;

    public CurrencyMismatchException(CashFlowId cashFlowId, Currency expectedCurrency, Currency actualCurrency) {
        super(String.format("CashFlow [%s] holds [%s]; money in [%s] cannot be recorded in it.",
                cashFlowId.id(), expectedCurrency.getId(), actualCurrency.getId()));
        this.cashFlowId = cashFlowId;
        this.expectedCurrency = expectedCurrency;
        this.actualCurrency = actualCurrency;
    }

    public CashFlowId getCashFlowId() {
        return cashFlowId;
    }

    public Currency getExpectedCurrency() {
        return expectedCurrency;
    }

    public Currency getActualCurrency() {
        return actualCurrency;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.CASHFLOW_CURRENCY_MISMATCH;
    }
}
