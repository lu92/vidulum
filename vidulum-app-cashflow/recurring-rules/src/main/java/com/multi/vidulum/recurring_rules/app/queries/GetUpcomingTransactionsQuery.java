package com.multi.vidulum.recurring_rules.app.queries;

import com.multi.vidulum.recurring_rules.domain.exceptions.InvalidDashboardParameterException;
import com.multi.vidulum.shared.cqrs.queries.Query;

/**
 * The transactions a person's active rules imply over the next {@code days}, soonest first, capped
 * at {@code limit}.
 *
 * <p>Ranges checked here for the same reason as {@link GetDashboardQuery}, and in the same order the
 * endpoint used: {@code days} before {@code limit}.
 */
public record GetUpcomingTransactionsQuery(
        String userId,
        String cashFlowId,
        int days,
        int limit
) implements Query {

    private static final int MAX_DAYS = 90;
    private static final int MAX_LIMIT = 100;

    public GetUpcomingTransactionsQuery {
        if (days < 1 || days > MAX_DAYS) {
            throw new InvalidDashboardParameterException("days", days, 1, MAX_DAYS);
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidDashboardParameterException("limit", limit, 1, MAX_LIMIT);
        }
    }
}
