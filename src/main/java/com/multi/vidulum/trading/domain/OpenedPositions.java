package com.multi.vidulum.trading.domain;

import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class OpenedPositions {
    PortfolioId portfolioId;
    List<Position> positions;
}
