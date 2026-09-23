package com.multi.vidulum.portfolio.domain.portfolio;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.domain.PortfolioNotFoundException;
import com.multi.vidulum.shared.ddd.DomainRepository;
import com.multi.vidulum.shared.ddd.event.DomainEvent;

import java.util.List;

public interface DomainPortfolioRepository extends DomainRepository<PortfolioId, Portfolio> {
    List<Portfolio> findByUserId(UserId userId);

    List<DomainEvent> findDomainEvents(PortfolioId portfolioId);

    /**
     * A portfolio, but only if it belongs to the caller (task G2).
     *
     * <p>Someone else's portfolio answers {@code not found} rather than {@code forbidden}: a
     * {@code 403} confirms the identifier exists, which is itself something the caller has no
     * right to learn. The same rule A9 settled for exchange connections.
     *
     * <p>Kept on the repository so every handler enforces it identically — a check each caller
     * writes for itself is a check some caller forgets.
     */
    default Portfolio findOwnedOrThrow(UserId userId, PortfolioId portfolioId) {
        return findById(portfolioId)
                .filter(portfolio -> portfolio.getUserId().equals(userId))
                .orElseThrow(() -> new PortfolioNotFoundException(portfolioId));
    }
}
