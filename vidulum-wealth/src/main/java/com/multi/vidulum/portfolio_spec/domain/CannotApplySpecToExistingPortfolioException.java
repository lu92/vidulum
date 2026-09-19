package com.multi.vidulum.portfolio_spec.domain;

import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * Confirming a specification computed against an existing portfolio is not implemented yet.
 *
 * <p>D3 covers creation — the onboarding case, where the known state is empty. Applying a
 * difference to a portfolio that already exists means increasing and decreasing positions, and
 * decreasing one whose cost is unknown is a taxable event we cannot compute (C7). Failing loudly
 * beats applying half of it.
 */
public class CannotApplySpecToExistingPortfolioException extends BusinessException {

    public CannotApplySpecToExistingPortfolioException(PortfolioId portfolioId) {
        super("Applying a specification to the existing portfolio [" + portfolioId.getId()
                + "] is not supported yet - only creation is");
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.PORTFOLIO_SPEC_APPLY_NOT_SUPPORTED;
    }
}
