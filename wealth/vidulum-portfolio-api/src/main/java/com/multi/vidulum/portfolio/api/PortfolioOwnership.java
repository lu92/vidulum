package com.multi.vidulum.portfolio.api;

import com.multi.vidulum.shared.module.ModuleApi;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.api.error.PortfolioNotFoundException;

/**
 * Who is asking, and whether the portfolio they named is theirs (task G2).
 *
 * <p>In the contract because four controllers in three modules — trading, pnl and risk — ask this
 * before doing anything with a portfolio id that arrived in a request. They used to ask it of
 * {@code PortfolioAccess}, a component of the portfolio module holding
 * {@code DomainPortfolioRepository}: to find out whether a portfolio was yours, another module
 * reached into the repository of somebody else's aggregate.
 *
 * <p>The implementation still lives in the portfolio module, where the repository is.
 */
public interface PortfolioOwnership extends ModuleApi {

    /** The caller, taken from the token and never from the request. */
    UserId currentUser();

    /**
     * Resolves a portfolio the caller owns.
     *
     * @throws PortfolioNotFoundException for a stranger's portfolio as well as a missing one —
     *         the caller may not learn which
     */
    PortfolioId requireOwned(String portfolioId);
}
