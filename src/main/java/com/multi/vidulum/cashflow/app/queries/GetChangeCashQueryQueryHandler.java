package com.multi.vidulum.cashflow.app.queries;

import com.multi.vidulum.cashflow.domain.CashChange;
import com.multi.vidulum.cashflow.domain.CashChangeDoesNotExistsException;
import com.multi.vidulum.cashflow.domain.DomainCashChangeRepository;
import com.multi.vidulum.cashflow.domain.snapshots.CashChangeSnapshot;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class GetChangeCashQueryQueryHandler implements QueryHandler<GetCashChangeQuery, CashChangeSnapshot> {
    private final DomainCashChangeRepository domainCashChangeRepository;

    @Override
    public CashChangeSnapshot query(GetCashChangeQuery query) {
        CashChange cashChange = domainCashChangeRepository.findById(query.cashChangeId())
                .orElseThrow(() -> new CashChangeDoesNotExistsException(query.cashChangeId()));
        return cashChange.getSnapshot();
    }
}
