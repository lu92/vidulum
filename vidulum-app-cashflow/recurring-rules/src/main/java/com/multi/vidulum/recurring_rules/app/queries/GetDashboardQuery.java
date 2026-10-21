package com.multi.vidulum.recurring_rules.app.queries;

import com.multi.vidulum.recurring_rules.domain.exceptions.InvalidDashboardParameterException;
import com.multi.vidulum.shared.cqrs.queries.Query;

/**
 * Everything the rules dashboard shows for one cash flow: how many rules there are by status, what
 * they add up to over the projection period, and the next few transactions due.
 *
 * <p><b>The ranges are checked here, not in the controller.</b> They used to be two {@code if}s in
 * the endpoint, which meant the limits held for requests arriving over HTTP and for nobody else -
 * and {@code projectionMonths = 0} would have reached {@code YearMonth.plusMonths(-1)} and quietly
 * produced a projection period ending before it starts. A query that cannot be built wrong needs no
 * guard at its callers.
 *
 * <p>The order of the two checks is preserved from that endpoint: a request breaking both answers
 * about {@code upcomingDays}, which is what {@code ErrorCode.RECURRING_RULE_INVALID_PARAMETER}
 * reports today.
 */
public record GetDashboardQuery(
        String userId,
        String cashFlowId,
        int upcomingDays,
        int projectionMonths
) implements Query {

    private static final int MAX_UPCOMING_DAYS = 90;
    private static final int MAX_PROJECTION_MONTHS = 12;

    public GetDashboardQuery {
        if (upcomingDays < 1 || upcomingDays > MAX_UPCOMING_DAYS) {
            throw new InvalidDashboardParameterException("upcomingDays", upcomingDays, 1, MAX_UPCOMING_DAYS);
        }
        if (projectionMonths < 1 || projectionMonths > MAX_PROJECTION_MONTHS) {
            throw new InvalidDashboardParameterException("projectionMonths", projectionMonths, 1, MAX_PROJECTION_MONTHS);
        }
    }
}
