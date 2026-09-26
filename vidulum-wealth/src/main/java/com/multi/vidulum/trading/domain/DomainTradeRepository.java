package com.multi.vidulum.trading.domain;

import com.multi.vidulum.common.OriginTradeId;
import com.multi.vidulum.common.TradeId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.shared.ddd.DomainRepository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface DomainTradeRepository extends DomainRepository<TradeId, Trade> {
    List<Trade> findByUserIdAndPortfolioId(UserId userId, PortfolioId portfolioId);

    /**
     * The trade this origin already named, if we have it (task F1) — what makes a redelivered
     * message, a re-exported CSV range and a double-clicked form land on one row instead of three.
     */
    Optional<Trade> findByOrigin(PortfolioId portfolioId, OriginTradeId originTradeId);

    List<Trade> findByUserIdAndPortfolioIdInDateRange(UserId userId, ZonedDateTime from, ZonedDateTime to);
}
