package com.multi.vidulum.portfolio.domain.portfolio;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.ddd.DomainRepository;

import java.util.List;

public interface DomainPortfolioRepository extends DomainRepository<PortfolioId, Portfolio> {
    List<Portfolio> findByUserId(UserId userId);
}
