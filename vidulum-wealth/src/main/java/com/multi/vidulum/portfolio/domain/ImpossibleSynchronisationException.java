package com.multi.vidulum.portfolio.domain;

import com.multi.vidulum.common.Quantity;
import com.multi.vidulum.common.Ticker;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * A synchronisation would take a position below zero (task D13).
 *
 * <p>A guard, not an expected path: differences are computed against what we hold, so a decrease
 * can only exceed the holding if the portfolio moved between the specification being built and
 * being applied — a trade recorded in between, or two specifications applied out of order.
 *
 * <p>Refused rather than clamped. Clamping would leave a portfolio that silently disagrees with
 * both the exchange and its own history, and nothing afterwards could tell that it had happened.
 */
public class ImpossibleSynchronisationException extends BusinessException {

    public ImpossibleSynchronisationException(Ticker ticker, Quantity held, Quantity removed) {
        super(String.format(
                "Synchronisation removes [%s] of [%s] but only [%s] is held; "
                        + "the portfolio changed since the specification was built",
                removed, ticker.getId(), held));
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.PORTFOLIO_SYNCHRONISATION_IMPOSSIBLE;
    }
}
