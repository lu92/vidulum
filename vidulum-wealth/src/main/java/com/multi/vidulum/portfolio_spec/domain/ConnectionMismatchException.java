package com.multi.vidulum.portfolio_spec.domain;

import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * The confirmation states something the connection contradicts.
 *
 * <p>Valuation currency and broker are decided when the exchange account is connected, not when
 * a portfolio is written: quotes are published against the valuation currency and must be in the
 * cache <b>before</b> the portfolio exists (decision 9), and the broker decides whose quote cache
 * serves it at all. Letting the confirmation set them again gives the same fact two sources.
 *
 * <p>Rejecting rather than silently overriding follows the pattern the cash-flow attestation
 * already uses with {@code confirmedBalance}: the caller states what they believe, and a
 * disagreement is surfaced instead of resolved behind their back. A caller who sends USD while
 * the connection says EUR has the wrong picture, and would otherwise find out much later — when
 * {@code GET /portfolio} fails on a quote nobody published.
 */
public class ConnectionMismatchException extends BusinessException {

    public ConnectionMismatchException(String field, String stated, String onConnection) {
        super(String.format(
                "%s stated as [%s] but the connection says [%s]", field, stated, onConnection));
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.PORTFOLIO_SPEC_CONNECTION_MISMATCH;
    }
}
