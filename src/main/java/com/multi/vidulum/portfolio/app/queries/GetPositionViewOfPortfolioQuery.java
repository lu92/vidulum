package com.multi.vidulum.portfolio.app.queries;

import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.cqrs.queries.Query;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class GetPositionViewOfPortfolioQuery implements Query {
    PortfolioId portfolioId;
}
