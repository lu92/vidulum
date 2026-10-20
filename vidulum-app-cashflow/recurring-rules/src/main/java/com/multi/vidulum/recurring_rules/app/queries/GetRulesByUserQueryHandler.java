package com.multi.vidulum.recurring_rules.app.queries;

import com.multi.vidulum.common.UserId;
import com.multi.vidulum.recurring_rules.domain.DomainRecurringRuleRepository;
import com.multi.vidulum.recurring_rules.domain.RecurringRule;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleSnapshot;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Every rule a person owns, across their cash flows.
 *
 * <p>Two endpoints send this query - {@code /user/{userId}} and {@code /me} - and the difference
 * between them belongs to the controller: one takes the id from the path, the other from the JWT.
 * The handler is told whose rules to fetch and does not ask who is asking.
 */
@Component
@AllArgsConstructor
public class GetRulesByUserQueryHandler
        implements QueryHandler<GetRulesByUserQuery, List<RecurringRuleSnapshot>> {

    private final DomainRecurringRuleRepository ruleRepository;

    @Override
    public List<RecurringRuleSnapshot> query(GetRulesByUserQuery query) {
        return ruleRepository.findByUserId(UserId.of(query.userId())).stream()
                .map(RecurringRule::getSnapshot)
                .toList();
    }
}
