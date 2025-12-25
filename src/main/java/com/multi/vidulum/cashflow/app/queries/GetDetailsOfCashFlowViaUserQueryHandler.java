package com.multi.vidulum.cashflow.app.queries;

import com.multi.vidulum.cashflow.domain.CashFlow;
import com.multi.vidulum.cashflow.domain.DomainCashFlowRepository;
import com.multi.vidulum.cashflow.domain.snapshots.CashFlowSnapshot;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@AllArgsConstructor
public class GetDetailsOfCashFlowViaUserQueryHandler implements QueryHandler<GetDetailsOfCashFlowViaUserQuery, List<CashFlowSnapshot>> {
    private final DomainCashFlowRepository domainCashFlowRepository;

    @Override
    public List<CashFlowSnapshot> query(GetDetailsOfCashFlowViaUserQuery query) {
        return domainCashFlowRepository.findDetailsByUserId(query.userId()).stream()
                .map(CashFlow::getSnapshot)
                .toList();
    }
}
