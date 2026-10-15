package com.multi.vidulum.portfolio_spec.domain;

import com.multi.vidulum.common.UserId;

import java.util.List;
import java.util.Optional;

public interface DomainPortfolioSpecRepository {

    PortfolioSpec save(PortfolioSpec spec);

    Optional<PortfolioSpec> findById(PortfolioSpecId id);

    List<PortfolioSpec> findByUserId(UserId userId);

    /**
     * A specification is visible only to the user it belongs to. Someone else's answers
     * {@code not found} rather than {@code forbidden}, for the same reason as
     * {@code ExchangeConnection}: a 403 would confirm the identifier exists.
     */
    default PortfolioSpec findOwnedOrThrow(UserId userId, PortfolioSpecId id) {
        return findById(id)
                .filter(spec -> spec.getUserId().equals(userId))
                .orElseThrow(() -> new PortfolioSpecNotFoundException(id));
    }
}
