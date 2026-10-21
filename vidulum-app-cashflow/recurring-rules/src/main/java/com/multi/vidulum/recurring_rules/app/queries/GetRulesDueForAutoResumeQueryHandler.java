package com.multi.vidulum.recurring_rules.app.queries;

import com.multi.vidulum.recurring_rules.domain.DomainRecurringRuleRepository;
import com.multi.vidulum.recurring_rules.domain.RecurringRule;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleSnapshot;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Answers with snapshots, not aggregates.
 *
 * <p>The scheduler needs three things per row - the id to send a command for, the name to log, and
 * the resume date to quote - and handing it live aggregates would invite it to write through them,
 * which is {@code AutoResumeRuleCommandHandler}'s job. A read gives back a value.
 */
@Component
@AllArgsConstructor
public class GetRulesDueForAutoResumeQueryHandler
        implements QueryHandler<GetRulesDueForAutoResumeQuery, List<RecurringRuleSnapshot>> {

    private final DomainRecurringRuleRepository ruleRepository;

    @Override
    public List<RecurringRuleSnapshot> query(GetRulesDueForAutoResumeQuery query) {
        return ruleRepository.findPausedRulesWithResumeDateOnOrBefore(query.date()).stream()
                .map(RecurringRule::getSnapshot)
                .toList();
    }
}
