package com.multi.vidulum.trading.domain;

import com.multi.vidulum.common.TradeId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.ddd.DomainRepository;

import java.time.ZonedDateTime;
import java.util.List;

public interface DomainTradeRepository extends DomainRepository<TradeId, Trade> {
    List<Trade> findByUserIdAndPortfolioId(UserId userId, PortfolioId portfolioId);

    List<Trade> findByUserIdAndPortfolioIdInDateRange(UserId userId, PortfolioId portfolioId, ZonedDateTime from, ZonedDateTime to);
}
