package com.multi.vidulum.portfolio_spec.domain;

import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * This specification has already been applied (task D8).
 *
 * <p>It used to be called {@code CannotApplySpecToExistingPortfolioException} and to say that
 * applying a difference to an existing portfolio was unsupported. D13 made it supported, and the
 * name outlived the truth — while the condition it actually guards never changed:
 * {@code portfolioId} is set by {@code markApplied}, so what it catches is a <b>second
 * confirmation of the same specification</b>.
 *
 * <p>Which is the guard that matters for idempotency: a confirmation that arrives twice — a
 * retried request, an impatient second click — must not apply the same differences again. The
 * second attempt changes nothing and says so.
 */
public class SpecificationAlreadyAppliedException extends BusinessException {

    public SpecificationAlreadyAppliedException(PortfolioSpecId id, PortfolioId portfolioId) {
        super("Specification [" + id.getId() + "] has already been applied as portfolio ["
                + portfolioId.getId() + "]; nothing was changed");
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.PORTFOLIO_SPEC_ALREADY_APPLIED;
    }
}
