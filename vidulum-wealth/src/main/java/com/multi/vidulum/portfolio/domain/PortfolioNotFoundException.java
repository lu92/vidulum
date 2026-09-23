package com.multi.vidulum.portfolio.domain;

import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.error.BusinessException;
import com.multi.vidulum.common.error.ErrorCode;

/**
 * No such portfolio — or none the caller is entitled to see (task G2).
 *
 * <p>Deliberately the same answer for both. A {@code 403} for a stranger's portfolio would confirm
 * that the identifier exists, which is itself something they have no right to learn.
 *
 * <p>Made a {@link BusinessException} here: as a plain {@code RuntimeException} it reached the
 * catch-all handler and came back as {@code 500}, so a refusal was indistinguishable from a broken
 * server. The same defect A3 fixed for the exchange module's exceptions.
 */
public class PortfolioNotFoundException extends BusinessException {

    public PortfolioNotFoundException(PortfolioId portfolioId) {
        super(String.format("Portfolio [%s] not found", portfolioId.getId()));
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.PORTFOLIO_NOT_FOUND;
    }
}
