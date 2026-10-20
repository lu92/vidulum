package com.multi.vidulum.recurring_rules.app.queries;

import com.multi.vidulum.recurring_rules.domain.DomainRecurringRuleRepository;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleId;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleSnapshot;
import com.multi.vidulum.recurring_rules.domain.exceptions.RuleNotFoundException;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * One rule, as its snapshot.
 *
 * <p>A rule that is not there is {@code RuleNotFoundException} - a 404 - rather than an empty
 * answer, because both callers asked for a rule by its id: the id came from somewhere, so its
 * absence is news.
 */
@Component
@AllArgsConstructor
public class GetRuleQueryHandler implements QueryHandler<GetRuleQuery, RecurringRuleSnapshot> {

    private final DomainRecurringRuleRepository ruleRepository;

    @Override
    public RecurringRuleSnapshot query(GetRuleQuery query) {
        RecurringRuleId ruleId = RecurringRuleId.of(query.ruleId());
        return ruleRepository.findById(ruleId)
                .orElseThrow(() -> new RuleNotFoundException(ruleId))
                .getSnapshot();
    }
}
