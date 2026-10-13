package com.multi.vidulum.portfolio.app.queries;

import com.multi.vidulum.common.Broker;
import com.multi.vidulum.common.Money;
import com.multi.vidulum.common.PortfolioId;
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
