package com.multi.vidulum.portfolio.app.queries;

import com.multi.vidulum.portfolio.domain.portfolio.PortfolioId;
import com.multi.vidulum.shared.cqrs.queries.Query;
import lombok.Value;

@Value
public class GetPortfolioQuery implements Query {
    PortfolioId portfolioId;
}
