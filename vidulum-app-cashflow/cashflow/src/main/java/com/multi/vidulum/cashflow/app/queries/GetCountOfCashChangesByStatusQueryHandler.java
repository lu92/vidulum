package com.multi.vidulum.cashflow.app.queries;

import com.multi.vidulum.cashflow.api.CashChangeCountsByStatus;
import com.multi.vidulum.cashflow.api.error.CashFlowNotFoundException;
import com.multi.vidulum.cashflow.domain.CashFlow;
import com.multi.vidulum.cashflow.domain.DomainCashFlowRepository;
import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Counts a source rule's cash changes by state, from the snapshot.
 *
 * <p>The snapshot rather than the aggregate's own fields, and a query handler rather than a method
 * on {@code CashFlow}: nothing is being decided here. The aggregate's job is the rules about
 * changing a cash flow; counting what is in one is a read, and reads go through the query side.
 *
 * <p>{@code other} covers rejected and archived. It exists so that a state nobody asked about
 * cannot disappear from a total - the consumer this replaced compared {@code "PENDING"} and
 * {@code "CONFIRMED"} as string literals against someone else's JSON, so the rest was silently
 * counted as neither and the caller's own total did not add up.
 */
@Component
@AllArgsConstructor
public class GetCountOfCashChangesByStatusQueryHandler
        implements QueryHandler<GetCountOfCashChangesByStatusQuery, CashChangeCountsByStatus> {

    private final DomainCashFlowRepository domainCashFlowRepository;

    @Override
    public CashChangeCountsByStatus query(GetCountOfCashChangesByStatusQuery query) {
        CashFlow cashFlow = domainCashFlowRepository.findById(query.cashFlowId())
                .orElseThrow(() -> new CashFlowNotFoundException(query.cashFlowId()));

        String sourceRuleId = query.sourceRuleId();
        if (sourceRuleId == null) {
            // A cash change entered by hand carries no rule, so no rule owns it
            return new CashChangeCountsByStatus(0, 0, 0);
        }

        int pending = 0;
        int confirmed = 0;
        int other = 0;
        for (CashChangeSnapshot cashChange : cashFlow.getSnapshot().cashChanges().values()) {
            if (!sourceRuleId.equals(cashChange.sourceRuleId())) {
                continue;
            }
            switch (cashChange.status()) {
                case PENDING -> pending++;
                case CONFIRMED -> confirmed++;
                case REJECTED, ARCHIVED -> other++;
            }
        }

        return new CashChangeCountsByStatus(pending, confirmed, other);
    }
}
