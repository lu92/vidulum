package com.multi.vidulum.pnl.app.queries;

import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.queries.Query;

import java.time.ZonedDateTime;

/** What a portfolio was worth at a past moment (task F9). */
public record GetPortfolioValuationQuery(
        UserId userId,
        PortfolioId portfolioId,
        ZonedDateTime at) implements Query {
}
