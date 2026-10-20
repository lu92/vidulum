package com.multi.vidulum.recurring_rules.app.queries;

import com.multi.vidulum.common.CashFlowId;
import com.multi.vidulum.recurring_rules.domain.DomainRecurringRuleRepository;
import com.multi.vidulum.recurring_rules.domain.RecurringRule;
import com.multi.vidulum.recurring_rules.domain.RecurringRuleSnapshot;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Every rule shaping one cash flow.
 *
 * <p>An empty list is a legitimate answer here, unlike {@link GetRuleQueryHandler}: a cash flow with
 * no rules is an ordinary cash flow, and this handler is not the right place to find out whether the
 * cash flow itself exists.
 */
@Component
@AllArgsConstructor
public class GetRulesByCashFlowQueryHandler
        implements QueryHandler<GetRulesByCashFlowQuery, List<RecurringRuleSnapshot>> {

    private final DomainRecurringRuleRepository ruleRepository;

    @Override
    public List<RecurringRuleSnapshot> query(GetRulesByCashFlowQuery query) {
        return ruleRepository.findByCashFlowId(CashFlowId.of(query.cashFlowId())).stream()
                .map(RecurringRule::getSnapshot)
                .toList();
    }
}
