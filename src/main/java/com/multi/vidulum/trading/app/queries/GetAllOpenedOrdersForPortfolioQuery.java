package com.multi.vidulum.trading.app.queries;

import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.cqrs.queries.Query;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class GetAllOpenedOrdersForPortfolioQuery implements Query {
    PortfolioId portfolioId;
}
