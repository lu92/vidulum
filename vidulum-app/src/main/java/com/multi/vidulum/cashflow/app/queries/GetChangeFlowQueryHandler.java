package com.multi.vidulum.cashflow.app.queries;

import com.multi.vidulum.cashflow.domain.CashFlow;
import com.multi.vidulum.cashflow.domain.CashFlowDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.DomainCashFlowRepository;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class GetChangeFlowQueryHandler implements QueryHandler<GetCashFlowQuery, CashFlowSnapshot> {
    private final DomainCashFlowRepository domainCashFlowRepository;

    @Override
    public CashFlowSnapshot query(GetCashFlowQuery query) {
        CashFlow cashFlow = domainCashFlowRepository.findById(query.cashFlowId())
                .orElseThrow(() -> new CashFlowDoesNotExistsException(query.cashFlowId()));

        return cashFlow.getSnapshot();
    }
}
