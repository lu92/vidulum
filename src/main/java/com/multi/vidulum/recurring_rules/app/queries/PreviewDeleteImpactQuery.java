package com.multi.vidulum.recurring_rules.app.queries;

import com.multi.vidulum.shared.cqrs.queries.Query;

/**
 * Query to preview the impact of deleting a recurring rule.
 * Returns information about future occurrences that would be removed,
 * generated transactions, and warnings/recommendations.
 */
public record PreviewDeleteImpactQuery(
        String ruleId,
        String authToken
) implements Query {
}
