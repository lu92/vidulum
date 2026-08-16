package com.multi.vidulum.portfolio.app.queries;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.queries.Query;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GetAggregatedPortfolioQuery implements Query {
    UserId userId;
}
