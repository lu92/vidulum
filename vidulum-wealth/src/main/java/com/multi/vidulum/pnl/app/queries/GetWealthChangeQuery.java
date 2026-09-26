package com.multi.vidulum.pnl.app.queries;

import com.multi.vidulum.common.PortfolioId;
import com.multi.vidulum.common.UserId;
import com.multi.vidulum.shared.cqrs.queries.Query;

import java.time.ZonedDateTime;

/** How much the owner's wealth changed since a given moment (task C14). */
public record GetWealthChangeQuery(
        UserId userId,
        PortfolioId portfolioId,
        ZonedDateTime since) implements Query {
}
