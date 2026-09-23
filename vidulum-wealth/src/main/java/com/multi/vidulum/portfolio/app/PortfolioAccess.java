package com.multi.vidulum.portfolio.app;

import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.auth.AuthenticatedUserProvider;
import com.multi.vidulum.portfolio.domain.portfolio.DomainPortfolioRepository;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Who is asking, and whether the portfolio they named is theirs (task G2).
 *
 * <p>Until this existed, {@code userId} arrived in the request body or the path and nothing
 * compared it with the token. Any authenticated caller could deposit into, read, and trade in a
 * stranger's portfolio — demonstrated against a running backend, three calls, all answering 200.
 *
 * <p><b>Enforced at the controller, deliberately not in the handlers.</b> The same handlers serve
 * requests that have no authenticated user at all: {@code PlaceOrderCommandHandler} reserves funds
 * through {@code LockAssetCommandHandler}, and a trade arriving over Kafka reaches
 * {@code ProcessTradeCommandHandler} with no one logged in. Putting the check there would either
 * break those paths or force a fake identity through them, and a fake identity in an ownership
 * check is worse than none. The boundary where a human is on the other end is the controller, so
 * that is where the question is asked.
 */
@Component
@AllArgsConstructor
public class PortfolioAccess {

    private final DomainPortfolioRepository repository;
    private final AuthenticatedUserProvider authenticatedUserProvider;

    /** The caller, taken from the token and never from the request. */
    public UserId currentUser() {
        return authenticatedUserProvider.getCurrentUserId();
    }

    /**
     * Resolves a portfolio the caller owns, or fails as though it did not exist.
     *
     * @throws com.multi.vidulum.portfolio.domain.PortfolioNotFoundException for a stranger's
     *         portfolio as well as a missing one — the caller may not learn which
     */
    public PortfolioId requireOwned(String portfolioId) {
        PortfolioId id = PortfolioId.of(portfolioId);
        repository.findOwnedOrThrow(currentUser(), id);
        return id;
    }
}
