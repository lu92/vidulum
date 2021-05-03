package com.multi.vidulum.trading.domain;

import com.multi.vidulum.common.TradeId;
import com.multi.vidulum.shared.ddd.DomainRepository;

public interface DomainTradeRepository extends DomainRepository<TradeId, Trade> {
}
