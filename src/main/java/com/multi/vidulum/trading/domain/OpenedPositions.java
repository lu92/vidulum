package com.multi.vidulum.trading.domain;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class OpenedPositions {
    PortfolioId portfolioId;
    Broker broker;
    Money totalPortfolioValue;
    List<Position> positions;
}
